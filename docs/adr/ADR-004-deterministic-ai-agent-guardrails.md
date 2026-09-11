# ADR-004: Deterministic AI Agent Guardrails & Human-in-the-Loop (HITL)

## Status
**Accepted**

## Context
Deploying Generative AI and Large Language Models (LLMs) into banking and fintech systems carries severe risks of hallucinations, prompt injection, and non-deterministic behavior. An autonomous agent tasked with triaging Dead Letter Queue (DLQ) poison pills or reconciling ledger discrepancies could inadvertently execute unauthorized write-offs or trigger incorrect reversal transactions.

## Decision
We implemented an **Autonomous Diagnostic Agent with Hard Deterministic Guardrails**:
1. **Separation of Diagnosis and Execution:**
   * The AI Agent operates diagnostic tools marked with `@Tool` (`inspectDlqPayload`, `queryTransactionAuditTrail`, `inspectLedgerAccount`) to gather facts and hypothesize root causes.
   * The agent **never** writes directly to financial balances or modifies transaction states autonomously without policy validation.
2. **Deterministic Safety Policy Engine (`FinancialPolicyGuardrails`):**
   Hard mathematical rules enforced in pure Java code, completely outside the LLM's prompt context:
   * **Rule 1 (Financial Value Threshold):** Any transaction exceeding **$10,000.00** strictly requires **Human-in-the-Loop (HITL)** approval.
   * **Rule 2 (Confidence Threshold):** If the agent confidence score is below **0.85**, automated actions are blocked.
   * **Rule 3 (Irreversible Actions):** Permanent financial write-offs (`WRITE_OFF`) and manual ledger adjustments (`MANUAL_REVERSAL_REQUIRED`) are unconditionally gated behind human sign-off.
3. **Immutable Forensic Persistence:**
   Every triage decision, evaluated tool output, and confidence score is recorded to MongoDB for compliance auditing.

## Alternatives Considered
* **Free-Form Autonomous Agent (ReAct loop with direct DB write tools):**
  * *Rejected:* Unacceptable risk in financial production environments. A hallucinated tool argument could drain customer funds.
* **Pure Static Rule Engine without AI:**
  * *Rejected:* Inability to parse unstructured error messages, stack traces, and variable third-party payload schemas from cloud DLQs.

## Consequences & Trade-offs
* **Safe Automation:** Low-risk, high-confidence events (transient retryable network timeouts, non-financial schema mismatches) are classified instantly, saving engineering on-call hours.
* **Regulatory Compliance:** Auditable evidence and human approvals satisfy banking and regulatory compliance requirements.
