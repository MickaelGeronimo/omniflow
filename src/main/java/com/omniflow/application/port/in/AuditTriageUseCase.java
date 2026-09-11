package com.omniflow.application.port.in;

import java.util.List;
import java.util.Map;

public interface AuditTriageUseCase {

    record TriageRequest(
            String triggerType, // DLQ_POISON_PILL, BATCH_DISCREPANCY, HIGH_VALUE_AUDIT
            String transactionId,
            String payload,
            String errorMessage
    ) {}

    record TriageVerdict(
            String incidentId,
            String transactionId,
            String rootCauseAnalysis,
            String recommendedAction,
            boolean requiresHumanApproval,
            double confidenceScore,
            List<String> toolsExecuted,
            Map<String, Object> evidence
    ) {}

    TriageVerdict triageIncident(TriageRequest request);
}
