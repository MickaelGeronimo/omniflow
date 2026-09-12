# ADR-001: Transactional Outbox Pattern with PostgreSQL SKIP LOCKED

## Status
**Accepted**

## Context
In OmniFlow, payment state transitions (funds reservations, settlements) trigger downstream events to AWS SNS/SQS. Writing to PostgreSQL and publishing to SNS in the same HTTP request causes dual-write inconsistencies:
1. If PostgreSQL commits and the SNS network call fails or times out, the event is lost.
2. If SNS publishes first and PostgreSQL fails to commit, downstream subscribers process phantom transfers.
3. Two-Phase Commit (XA) is slow, fragile across cloud brokers, and not supported by AWS SNS/SQS.

## Decision
We use the **Transactional Outbox Pattern** with an `outbox_events` table in PostgreSQL. The payment record, ledger journal entry, and outbox event are committed in the same database transaction so they succeed or fail together.

Workers poll for unpublished events using `FOR UPDATE SKIP LOCKED` so multiple pods can read batches concurrently without blocking each other:
```sql
SELECT * FROM outbox_events
WHERE status IN ('PENDING', 'FAILED') AND retry_count < 3
ORDER BY created_at ASC
FOR UPDATE SKIP LOCKED
LIMIT 50;
```

## Alternatives Considered
* **Debezium CDC via PostgreSQL WAL:**
  * *Pros:* No application polling queries.
  * *Cons:* Requires running Kafka, Kafka Connect, and schema registry infrastructure, adding operational overhead at this stage.
* **Direct Publish without Outbox:**
  * *Cons:* Messages are lost if the application crashes between the database commit and the broker call.

## Consequences & Trade-offs
* **At-Least-Once Delivery:** Eliminates dual-write bugs. Downstream consumers need idempotency to handle duplicate events from retries.
* **Multiple Workers:** `FOR UPDATE SKIP LOCKED` lets several pods poll the outbox table in parallel without lock contention or duplicate reads.
* **Retries & Dead Letters:** Events that fail 3 attempts move to `DEAD_LETTER` for incident triage.
