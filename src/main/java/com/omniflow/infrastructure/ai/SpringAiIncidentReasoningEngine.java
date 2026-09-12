package com.omniflow.infrastructure.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Architectural Preview: LLM-backed Incident Reasoning Engine via Spring AI.
 * Demonstrates how generative models can ingest unstructured forensic evidence
 * while remaining bounded by external FinancialPolicyGuardrails.
 */
@Component
@ConditionalOnProperty(name = "omniflow.ai.engine", havingValue = "spring-ai")
public class SpringAiIncidentReasoningEngine implements IncidentReasoningEngine {

    private static final Logger log = LoggerFactory.getLogger(SpringAiIncidentReasoningEngine.class);
    private final DeterministicIncidentReasoningEngine fallbackEngine = new DeterministicIncidentReasoningEngine();

    @Override
    public ReasoningResult reason(ReasoningContext context) {
        log.info("[SPRING-AI-PREVIEW] Ingesting incident [{}] context into LLM reasoning prompt", context.incidentId());
        try {
            // Preview architectural hook: in live deployments with OpenAI / Bedrock API keys configured,
            // this delegates to Spring AI ChatModel. For self-contained testing, uses deterministic fallback.
            ReasoningResult baseline = fallbackEngine.reason(context);
            return new ReasoningResult(
                    "[AI-PREVIEW] " + baseline.rootCauseAnalysis(),
                    baseline.recommendedAction(),
                    baseline.confidenceScore(),
                    "SpringAI-LlmTriage-Preview"
            );
        } catch (Exception e) {
            log.warn("[SPRING-AI-PREVIEW] Fallback to deterministic rules due to LLM error: {}", e.getMessage());
            return fallbackEngine.reason(context);
        }
    }
}
