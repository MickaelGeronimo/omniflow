package com.omniflow.infrastructure.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record SubmitTransactionRequestDto(
        String idempotencyKey,

        @NotBlank(message = "referenceId is required")
        String referenceId,

        @NotBlank(message = "debtorAccount is required")
        String debtorAccount,

        @NotBlank(message = "creditorAccount is required")
        String creditorAccount,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be strictly positive")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        String currency,

        String description
) {}
