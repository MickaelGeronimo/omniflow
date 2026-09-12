# ADR-005: Distributed SHA-256 Idempotency Engine

## Status
**Accepted**

## Context
In payment gateways and distributed settlement pipelines, network timeouts frequently cause client SDKs, upstream checkout frontends, or automated retriers to resend requests. Without robust idempotency:
1. Double-charges occur when duplicate requests hit the payment orchestrator concurrently.
2. Attackers or buggy clients can reuse an idempotency key with altered payload values (e.g. attempting to pay a higher amount or change recipient under a previously approved key).

## Decision
We implemented a **Stateful Distributed Idempotency Engine** with cryptographic payload fingerprinting:
1. **Cryptographic Fingerprint:**
   A deterministic SHA-256 hash is computed across all critical request attributes:
   $$\text{Hash} = \text{SHA-256}(\text{referenceId} \parallel \text{debtor} \parallel \text{creditor} \parallel \text{amount} \parallel \text{currency} \parallel \text{description})$$
2. **Three-Phase State Machine:**
   * **`tryAcquire(idempotencyKey, fingerprint)`:**
     * If key is new: Inserts record in state `IN_FLIGHT` with an initial 2-minute lease.
     * If key exists and is `COMPLETED`: Returns cached `TransactionResult` without executing business logic.
     * If key exists with a *different* fingerprint: Throws `ConflictingPayloadException` (HTTP 409 Conflict).
     * If key exists and is `IN_FLIGHT` with an expired lease (> 2 min): Reclaims the lock to recover from previous node crashes.
     * If key exists and is currently active `IN_FLIGHT`: Rejects concurrent execution with lock contention.
   * **`markCompleted(idempotencyKey, result)`:** Caches the final JSON result, transitions state to `COMPLETED`, and extends retention to 24 hours (`expires_at = now() + 24h`).
   * **`releaseLock(idempotencyKey)`:** Releases the lock if business logic threw an unexpected transient exception, allowing safe client retries.

## Alternatives Considered
* **Redis Distributed Lock (Redlock):**
  * *Cons:* Redis memory volatility. If Redis restarts or drops keys, duplicate financial transactions can be executed. Storing idempotency keys in PostgreSQL guarantees ACID durability alongside ledger records.
* **Simple In-Memory Cache (Caffeine/Guava):**
  * *Cons:* Fails completely in multi-pod Kubernetes deployments where requests are load-balanced across instances.

## Consequences & Trade-offs
* **Zero Duplicate Debits:** Guaranteed even under severe network retry storms.
* **Tampering Prevention:** Identical keys with modified amounts or accounts are immediately blocked and flagged.
