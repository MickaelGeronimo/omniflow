package com.omniflow.infrastructure.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AuditTriageRequestDto(
        @NotBlank(message = "triggerType is required (e.g. DLQ_POISON_PILL, BATCH_DISCREPANCY)")
        String triggerType,

        @NotBlank(message = "transactionId is required")
        String transactionId,

        String payload,
        String errorMessage
) {}
