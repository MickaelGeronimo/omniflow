package com.omniflow.infrastructure.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Deterministic rule-based reasoning engine.
 * Performs root-cause triage based on forensic evidence
 * from DLQ payloads and audit logs without external network calls.
 */
@Component
@Primary
public class DeterministicIncidentReasoningEngine implements IncidentReasoningEngine {

    @Override
    public ReasoningResult reason(ReasoningContext context) {
        String suspectedCause = (String) context.dlqInspection().getOrDefault("suspectedCause", "UNKNOWN_CAUSE");
        String rootCauseAnalysis;
        String recommendedAction;
        double confidenceScore;

        if ("TRANSIENT_INSUFFICIENT_FUNDS_DURING_SETTLEMENT".equals(suspectedCause)) {
            rootCauseAnalysis = "Customer account balance depleted concurrently between funds hold and final settlement window.";
            recommendedAction = "MANUAL_REVERSAL_REQUIRED";
            confidenceScore = 0.94;
        } else if ("CORRUPTED_PAYLOAD_MISSING_CREDITOR".equals(suspectedCause)) {
            rootCauseAnalysis = "Message schema corruption: creditor account field missing from SQS payload.";
            recommendedAction = "QUARANTINE_POISON_PILL";
            confidenceScore = 0.98;
        } else {
            rootCauseAnalysis = "Transient network error or timeout during async SQS delivery.";
            recommendedAction = "REPLAY_TRANSACTION_FROM_OUTBOX";
            confidenceScore = 0.88;
        }

        return new ReasoningResult(
                rootCauseAnalysis,
                recommendedAction,
                confidenceScore,
                "DeterministicRuleEngine-v1"
        );
    }
}
