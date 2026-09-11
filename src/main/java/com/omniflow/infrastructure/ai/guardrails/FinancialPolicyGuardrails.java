package com.omniflow.infrastructure.ai.guardrails;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class FinancialPolicyGuardrails {

    private final BigDecimal humanApprovalThreshold;
    private final double confidenceThreshold;

    public FinancialPolicyGuardrails(
            @Value("${omniflow.ai.human-approval-threshold:10000.00}") BigDecimal humanApprovalThreshold,
            @Value("${omniflow.ai.confidence-threshold:0.85}") double confidenceThreshold) {
        this.humanApprovalThreshold = humanApprovalThreshold;
        this.confidenceThreshold = confidenceThreshold;
    }

    public boolean requiresHumanApproval(BigDecimal transactionAmount, double agentConfidence, String action) {
        // Rule 1: Any financial compensation or manual override > threshold requires human sign-off
        if (transactionAmount != null && transactionAmount.compareTo(humanApprovalThreshold) > 0) {
            return true;
        }

        // Rule 2: Low confidence AI decisions cannot be auto-executed
        if (agentConfidence < confidenceThreshold) {
            return true;
        }

        // Rule 3: Irreversible actions (ledger write-off / manual credit) require human confirmation
        return "MANUAL_REVERSAL_REQUIRED".equalsIgnoreCase(action) 
            || "WRITE_OFF".equalsIgnoreCase(action);
    }
}
