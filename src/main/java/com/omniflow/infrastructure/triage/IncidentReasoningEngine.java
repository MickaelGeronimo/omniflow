package com.omniflow.infrastructure.triage;

import java.util.Map;

public interface IncidentReasoningEngine {

    ReasoningResult reason(ReasoningContext context);

    record ReasoningContext(
            String incidentId,
            String transactionId,
            String triggerType,
            Map<String, Object> dlqInspection,
            Map<String, Object> auditTrail
    ) {}

    record ReasoningResult(
            String rootCauseAnalysis,
            String recommendedAction,
            double confidenceScore,
            String engineModel
    ) {}
}
