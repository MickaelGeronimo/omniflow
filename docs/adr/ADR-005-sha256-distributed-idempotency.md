# ADR-005: Distributed SHA-256 Idempotency Engine

## Status
**Accepted**

## Context
Network timeouts between clients and the API cause retries. Without idempotency handling:
1. Retried requests would create duplicate charges or double-postings in the ledger.
2. A client could reuse an existing idempotency key with different amounts or accounts, which must be rejected.

## Decision
We store idempotency records in PostgreSQL and validate request bodies with a SHA-256 fingerprint:
1. **Request Fingerprint:**
   A SHA-256 hash is computed across payment fields:
   $$\text{Hash} = \text{SHA-256}(\text{referenceId} \parallel \text{debtor} \parallel \text{creditor} \parallel \text{amount} \parallel \text{currency} \parallel \text{description})$$
2. **Key Lifecycle:**
   * **`tryAcquire(idempotencyKey, fingerprint)`:**
     * If key is new: Inserts record in state `IN_FLIGHT` with an initial 2-minute lease.
     * If key exists and is `COMPLETED`: Returns cached `TransactionResult` without re-executing.
     * If key exists with a *different* fingerprint: Throws `ConflictingPayloadException` (HTTP 409 Conflict).
     * If key exists in `IN_FLIGHT` with expired lease (> 2 min): Reclaims the lease to recover from previous worker crashes.
     * If key is actively `IN_FLIGHT`: Rejects concurrent execution.
   * **`markCompleted(idempotencyKey, result)`:** Saves the JSON result, transitions state to `COMPLETED`, and sets retention to 24 hours.
   * **`releaseLock(idempotencyKey)`:** Clears `IN_FLIGHT` on transient failure, allowing clean client retries.

## Alternatives Considered
* **Redis Distributed Lock (Redlock):**
  * *Cons:* If Redis restarts or drops unpersisted keys, duplicate payments could go through. Using PostgreSQL keeps the idempotency key in the same durable database as the ledger.
* **In-Memory Cache (Caffeine/Guava):**
  * *Cons:* Does not work across multiple application instances behind a load balancer.

## Consequences & Trade-offs
* **No Duplicate Charges:** Retries return the original cached response instead of charging again.
* **Conflict Detection:** Reusing a key with different parameters returns HTTP 409 Conflict.
