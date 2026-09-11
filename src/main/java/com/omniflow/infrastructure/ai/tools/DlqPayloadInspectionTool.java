package com.omniflow.infrastructure.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DlqPayloadInspectionTool {

    private final ObjectMapper objectMapper;

    public DlqPayloadInspectionTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Inspect and parse a Dead Letter Queue (DLQ) poison-pill message to extract failure context")
    public Map<String, Object> inspectDlqPayload(String rawPayload, String errorMessage) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(rawPayload, Map.class);
            return Map.of(
                    "parsed", true,
                    "payload", parsed,
                    "errorMessage", errorMessage != null ? errorMessage : "None provided",
                    "suspectedCause", detectPotentialCause(errorMessage, parsed)
            );
        } catch (Exception e) {
            return Map.of(
                    "parsed", false,
                    "rawPayload", rawPayload,
                    "errorMessage", errorMessage != null ? errorMessage : "None provided",
                    "parseError", e.getMessage()
            );
        }
    }

    private String detectPotentialCause(String error, Map<String, Object> payload) {
        if (error != null && error.contains("insufficient funds")) {
            return "TRANSIENT_INSUFFICIENT_FUNDS_DURING_SETTLEMENT";
        }
        if (!payload.containsKey("creditorAccount")) {
            return "CORRUPTED_PAYLOAD_MISSING_CREDITOR";
        }
        return "UNCLASSIFIED_SETTLEMENT_ERROR";
    }
}
