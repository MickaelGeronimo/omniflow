package com.omniflow.infrastructure.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Architectural integration point and preview hook for future LLM-backed triage.
 *
 * NOTE: Architectural integration point only. No external LLM network invocation
 * is performed in the current implementation. Serves as an extensible port adapter
 * prepared for Spring AI ChatModel integration, maintaining zero external runtime dependencies.
 */
@Component
@ConditionalOnProperty(name = "omniflow.ai.engine", havingValue = "spring-ai")
public class SpringAiIncidentReasoningEngine implements IncidentReasoningEngine {

    private static final Logger log = LoggerFactory.getLogger(SpringAiIncidentReasoningEngine.class);
    private final DeterministicIncidentReasoningEngine fallbackEngine = new DeterministicIncidentReasoningEngine();

    @Override
    public ReasoningResult reason(ReasoningContext context) {
        log.info("[AI-PREVIEW] Ingesting incident [{}] context into reasoning preview contract", context.incidentId());
        // Architectural preview hook: executes baseline diagnostic analysis without external network calls.
        // In a production environment with an active LLM provider configured, this method maps to a Spring AI ChatModel.
        ReasoningResult baseline = fallbackEngine.reason(context);
        return new ReasoningResult(
                "[AI-PREVIEW] " + baseline.rootCauseAnalysis(),
                baseline.recommendedAction(),
                baseline.confidenceScore(),
                "SpringAI-LlmTriage-Preview"
        );
    }
}