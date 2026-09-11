package com.omniflow.infrastructure.adapter.in.web.dto;

import com.omniflow.domain.model.TransactionStatus;

import java.time.Instant;

public record TransactionResponseDto(
        String transactionId,
        String referenceId,
        String debtorAccount,
        String creditorAccount,
        String amount,
        String currency,
        TransactionStatus status,
        String failureReason,
        Instant timestamp
) {}
