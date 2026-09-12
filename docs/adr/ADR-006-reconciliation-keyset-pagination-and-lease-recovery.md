# ADR-006: Keyset Pagination, Outbox Lease Recovery & Resilient Reasoning

## Status
**Accepted**

## Context
As financial ledgers grow into millions of journal entries and outbox events, two critical distributed systems failure modes emerge:
1. **Offset Pagination Degradation & Phantom Rows:** Traditional batch reconciliation querying LIMIT 100 OFFSET :offset scales at (N)$ execution cost. Furthermore, concurrent transaction insertions during reconciliation shift offsets dynamically, leading to skipped or double-counted financial records.
2. **Outbox Worker Abandonment on Pod Eviction:** When a Kubernetes worker pod crashes while processing an outbox batch marked PROCESSING, records remain abandoned indefinitely unless an automatic lease recovery mechanism reclaims them.
3. **Thundering Herd on Outbox Retries:** If external messaging downstream (AWS SNS/SQS) experiences transient throttling, synchronous fixed-interval retries cause thundering herd surges.
4. **AI Reasoning Predictability in Regulated Systems:** Unconstrained LLM reasoning in financial triage workflows introduces non-deterministic hallucination risks.

## Decision
1. **Keyset (Cursor) Pagination with Cutoff Snapshots:**
   * Replace offset pagination with ordered cursor scans: WHERE (:lastEntryId IS NULL OR j.entryId > :lastEntryId) AND j.timestamp <= :cutoff ORDER BY j.entryId ASC LIMIT 100.
   * Temporal cutoff (:cutoff) freezes the batch scope, ensuring 100% reproducible reconciliation results across runs.
2. **Outbox Lease Expiration & Worker Reclaiming:**
   * Outbox events in PROCESSING whose locked_at timestamp exceeds the lease window (5 minutes) are automatically reclaimed alongside PENDING events via indClaimableEventsWithSkipLocked.
3. **Exponential Backoff with Full Random Jitter:**
   * Retry intervals are computed as: \text{delay} = \min(300\text{s}, 2 \cdot 2^{\text{retryCount}}) + \text{jitter}(0, 1000\text{ms})
4. **Decoupled IncidentReasoningEngine:**
   * Domain and audit agents interact exclusively with the IncidentReasoningEngine port.
   * DeterministicIncidentReasoningEngine serves production traffic with zero hallucinations.
   * SpringAiIncidentReasoningPreview provides an architectural preview hook for LLM reasoning while keeping FinancialPolicyGuardrails strictly external to the AI model.

## Consequences & Trade-offs
* **Scalability:** Reconciliation memory footprint and database query execution remain strictly bounded and proportional to the configured chunk size regardless of table size.
* **Resilience:** Guaranteed automated recovery from worker crashes without manual operational intervention.
* **Safety:** Financial decisions, balance validations, and human-in-the-loop limits are mathematically enforced outside the AI context.
