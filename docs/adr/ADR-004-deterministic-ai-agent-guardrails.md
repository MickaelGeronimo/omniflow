# ADR-004: Incident Triage & Policy Guardrails

## Status
**Accepted**

## Context
Heuristic and AI triage models must not be responsible for financial authorization or balance changes. They can assist in classifying operational incidents and extracting forensic evidence, but all financial mutations remain strictly governed by deterministic ledger rules and human approval (HITL).

When handling Dead Letter Queue (DLQ) messages or ledger discrepancies, automated operations need clear boundaries to prevent incorrect reversals or write-offs.

## Decision
We separated incident diagnosis from action execution using policy guardrails:
1. **Diagnosis vs Execution:**
   * Diagnostic tools (`DlqPayloadInspectionTool`, `MongoAuditInspectionTool`, `LedgerInspectionTool`) collect event data, inspect payloads, and check balances.
   * The triage engine does not write directly to financial balances or modify transaction status.
2. **Policy Guardrails (`FinancialPolicyGuardrails`):**
   Evaluated in application code before any recommended action can proceed:
   * **Value Threshold:** Any transaction exceeding **$10,000.00** requires **Human-in-the-Loop (HITL)** approval.
   * **Confidence Threshold:** If the diagnostic confidence score is below **0.85**, automated actions are blocked.
   * **Irreversible Actions:** Actions like `WRITE_OFF` and `MANUAL_REVERSAL_REQUIRED` always require operator approval.
3. **Audit Records:**
   Every triage decision, tool result, and confidence score is saved to MongoDB for audit review.

## Alternatives Considered
* **Direct Database Remediation:**
  * *Rejected:* Unvalidated actions could modify balances or write off funds without controls.
* **Pure Hardcoded Rules without Extensibility:**
  * *Rejected:* Less flexible for parsing unstructured error messages, stack traces, and variable payload schemas from cloud DLQs.

## Consequences & Trade-offs
* **Safe Automation:** Low-risk, high-confidence events (such as known retryable timeouts) can be classified automatically without waking up on-call engineers.
* **Separation of Policy:** Guardrails are evaluated outside the reasoning engine, so changing the reasoning implementation does not change the financial rules.
* **Audit Trail:** High-value or irreversible actions always require operator approval and remain logged in MongoDB.
