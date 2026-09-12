# ADR-005: Distributed SHA-256 Idempotency Engine

## Status
**Accepted**

## Context
In payment and settlement pipelines, network timeouts cause clients or automated retriers to resend requests. Without idempotency controls:
1. Duplicate requests cause double-charging or repeated ledger postings.
2. Reusing an idempotency key with modified amounts or accounts must be detected and rejected.

## Decision
We implemented a distributed idempotency engine using PostgreSQL and SHA-256 request fingerprinting:
1. **Request Fingerprint:**
   A SHA-256 hash is computed across payment fields:
   $$\text{Hash} = \text{SHA-256}(\text{referenceId} \parallel \text{debtor} \parallel \text{creditor} \parallel \text{amount} \parallel \text{currency} \parallel \text{description})$$
2. **Lifecycle:**
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
  * *Cons:* Storing idempotency keys in PostgreSQL guarantees durability alongside ledger records without managing a separate Redis cluster.
* **In-Memory Cache (Caffeine/Guava):**
  * *Cons:* Does not work across multiple application instances behind a load balancer.

## Consequences & Trade-offs
* **Duplicate Prevention:** Prevents double-charging on network retries and concurrent client submissions.
* **Conflict Detection:** Reusing a key with different parameters returns HTTP 409 Conflict.
