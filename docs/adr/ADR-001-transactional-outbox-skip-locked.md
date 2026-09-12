# ADR-001: Transactional Outbox Pattern with PostgreSQL SKIP LOCKED

## Status
**Accepted**

## Context
In OmniFlow, payment state transitions (funds reservations, settlements) trigger downstream events to AWS SNS/SQS. Writing to PostgreSQL and publishing to SNS in the same HTTP request causes dual-write inconsistencies:
1. If PostgreSQL commits and the SNS network call fails or times out, the event is lost.
2. If SNS publishes first and PostgreSQL fails to commit, downstream subscribers process phantom transfers.
3. Two-Phase Commit (XA) is slow, fragile across cloud brokers, and not supported by AWS SNS/SQS.

## Decision
We implemented the **Transactional Outbox Pattern** using an `outbox_events` table in PostgreSQL. The business entity, ledger journal entry, and outbox event record are committed in the same local database transaction.

To poll and publish events across multiple instances without lock contention, workers query:
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
* **At-Least-Once Delivery:** Eliminates dual-write inconsistencies between database state and event publishing. Downstream consumers require idempotency (handled via SHA-256 fingerprinting).
* **Concurrent Relays:** `FOR UPDATE SKIP LOCKED` allows multiple worker instances to process batches concurrently without blocking each other or republishing the same event.
* **Retries & DLQ:** Events failing after 3 attempts transition to `DEAD_LETTER` for incident triage.
