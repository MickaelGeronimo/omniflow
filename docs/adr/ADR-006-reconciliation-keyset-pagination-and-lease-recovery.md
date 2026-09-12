# ADR-006: Keyset Pagination, Outbox Lease Recovery & Incident Reasoning

## Status
**Accepted**

## Context
As ledger and outbox tables grow, several operational challenges appear:
1. **Offset Pagination Drift:** Offset-based queries (`OFFSET :n`) become slower on deep pages and can duplicate or skip records if new transactions are inserted during a batch reconciliation run.
2. **Abandoned Outbox Events:** If a worker crashes while processing a batch marked `PROCESSING`, those records remain stuck unless reclaimed.
3. **Thundering Herd on Retries:** Fixed retry intervals against throttled downstream brokers cause retry spikes.
4. **Separation of Reasoning:** Incident triage needs a clean boundary so diagnostic logic can run without coupling the transaction core to an external model provider.

## Decision
1. **Keyset Pagination with Temporal Cutoff:**
   * Uses ordered cursor scans: `WHERE (:lastEntryId IS NULL OR j.entryId > :lastEntryId) AND j.timestamp <= :cutoff ORDER BY j.entryId ASC LIMIT 100`.
   * The cutoff timestamp freezes the reconciliation window so newly inserted transactions don't affect the running batch.
2. **Outbox Lease Recovery:**
   * Events in `PROCESSING` whose `locked_at` exceeds 5 minutes are reclaimed alongside `PENDING` events via `findClaimableEventsWithSkipLocked`.
3. **Exponential Backoff with Jitter:**
   * Retries calculate delay as: $\min(300\text{s}, 2 \cdot 2^{\text{retry}}) + \text{jitter}$.
4. **Pluggable Incident Reasoning:**
   * Application services interact with the `IncidentReasoningEngine` port.
   * `DeterministicIncidentReasoningEngine` provides local rule evaluation without external calls.
   * `SpringAiIncidentReasoningPreview` provides an adapter stub for future model integrations.

## Consequences & Trade-offs
* **Bounded Memory:** Reconciliation processes one page at a time instead of loading large result sets into memory.
* **Lease Recovery:** Stale `PROCESSING` events from crashed workers are automatically retried.
* **Safety Rules:** Policy guardrails and human review thresholds are evaluated independently of the reasoning engine implementation.
