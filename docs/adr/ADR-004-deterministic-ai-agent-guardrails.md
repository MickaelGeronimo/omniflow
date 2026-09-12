# ADR-004: AI Incident Triage Engine & Deterministic Safety Guardrails

## Status
**Accepted**

## Context
Integrating external LLM providers into automated financial pipelines introduces non-determinism and operational risks. Automated triage of Dead Letter Queue (DLQ) messages or ledger discrepancies must not execute unverified write-offs or ledger reversals without explicit verification and policy controls.

## Decision
We implemented an **Automated Incident Triage Engine with Deterministic Guardrails**:
1. **Separation of Diagnosis and Execution:**
   * The diagnostic tools (`inspectDlqPayload`, `queryTransactionAuditTrail`, `inspectLedgerAccount`) gather forensic facts and inspect payloads.
   * The triage engine **never** writes directly to financial balances or modifies transaction states without policy validation.
2. **Deterministic Safety Policy Engine (`FinancialPolicyGuardrails`):**
   Hard mathematical rules enforced in pure Java code, completely outside the LLM's prompt context:
   * **Rule 1 (Financial Value Threshold):** Any transaction exceeding **$10,000.00** strictly requires **Human-in-the-Loop (HITL)** approval.
   * **Rule 2 (Confidence Threshold):** If the agent confidence score is below **0.85**, automated actions are blocked.
   * **Rule 3 (Irreversible Actions):** Permanent financial write-offs (`WRITE_OFF`) and manual ledger adjustments (`MANUAL_REVERSAL_REQUIRED`) are unconditionally gated behind human sign-off.
3. **Immutable Forensic Persistence:**
   Every triage decision, evaluated tool output, and confidence score is recorded to MongoDB for compliance auditing.

## Alternatives Considered
* **Direct Database Remediation Loop:**
  * *Rejected:* Unacceptable risk in financial systems. Unvalidated tool arguments could modify balances or write off funds without controls.
* **Pure Hardcoded Rule Engine without Extensibility:**
  * *Rejected:* Less flexible for parsing varied error messages, stack traces, and third-party payload schemas from cloud DLQs.

## Consequences & Trade-offs
* **Safe Automation:** Low-risk, high-confidence events (transient retryable network timeouts, non-financial schema mismatches) are classified instantly, saving engineering on-call hours.
* **Regulatory Compliance:** Auditable evidence and human approvals satisfy banking and regulatory compliance requirements.
