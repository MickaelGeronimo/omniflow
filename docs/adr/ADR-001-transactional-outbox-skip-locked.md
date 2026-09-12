# ADR-001: Transactional Outbox Pattern with PostgreSQL SKIP LOCKED

## Status
**Accepted**

## Context
In a distributed financial platform like OmniFlow, payment state transitions (funds reservations, settlements) must trigger downstream events to AWS SNS/SQS. A naive dual-write approach (writing to PostgreSQL and publishing to SNS in the same HTTP request) causes dual-write anomalies:
1. If PostgreSQL commits and the SNS network call times out, the message is lost forever.
2. If SNS publishes first and PostgreSQL fails to commit, downstream subscribers process phantom financial transfers.
3. Distributed 2PC (Two-Phase Commit / XA) is notoriously slow, brittle across cloud message brokers, and not supported by AWS SNS/SQS.

## Decision
We implemented the **Transactional Outbox Pattern** using an `outbox_events` table in PostgreSQL. The business entity, ledger journal entry, and the outbox event record are committed in the **exact same local ACID database transaction**.

To poll and relay events to AWS SNS with high throughput across multiple horizontally-scaled instances, we use:
```sql
SELECT * FROM outbox_events
WHERE status IN ('PENDING', 'FAILED') AND retry_count < 3
ORDER BY created_at ASC
FOR UPDATE SKIP LOCKED
LIMIT 50;
```

## Alternatives Considered
* **Debezium CDC (Change Data Capture) via PostgreSQL WAL:**
  * *Pros:* Zero application polling overhead.
  * *Cons:* Adds high operational complexity (Kafka Connect cluster, schema registry, ZooKeeper/KRaft), increases infrastructure costs for low-to-medium clusters, and complicates poison pill handling.
* **Distributed Sagas without Outbox:**
  * *Cons:* Inability to guarantee message publishing if the orchestrator process crashes before emitting the event.

## Consequences & Trade-offs
* **Guaranteed At-Least-Once Delivery:** Eliminates dual-write inconsistencies between local database state and event publishing, providing at-least-once delivery semantics. Downstream consumers require idempotency (which OmniFlow enforces via SHA-256 fingerprinting).
* **Zero Lock Contention:** `FOR UPDATE SKIP LOCKED` allows multiple relay worker pods to run concurrently without blocking each other or republishing the same event.
* **Exponential Backoff & Dead-Lettering:** Events failing after 3 attempts transition to `DEAD_LETTER` for autonomous AI triage.
