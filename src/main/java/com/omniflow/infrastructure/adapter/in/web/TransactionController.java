package com.omniflow.infrastructure.adapter.in.web;

import com.omniflow.application.port.in.SubmitTransactionUseCase;
import com.omniflow.domain.model.AccountId;
import com.omniflow.domain.model.Money;
import com.omniflow.infrastructure.adapter.in.web.dto.SubmitTransactionRequestDto;
import com.omniflow.infrastructure.adapter.in.web.dto.TransactionResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final SubmitTransactionUseCase submitTransactionUseCase;

    public TransactionController(SubmitTransactionUseCase submitTransactionUseCase) {
        this.submitTransactionUseCase = submitTransactionUseCase;
    }

    @PostMapping
    public ResponseEntity<TransactionResponseDto> submitTransaction(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody SubmitTransactionRequestDto request) {

        String key = (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank())
                ? idempotencyKeyHeader
                : (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()
                    ? request.idempotencyKey()
                    : "IDEMP-" + UUID.randomUUID());

        SubmitTransactionUseCase.SubmitCommand command = new SubmitTransactionUseCase.SubmitCommand(
                key,
                request.referenceId(),
                AccountId.parse(request.debtorAccount()),
                AccountId.parse(request.creditorAccount()),
                Money.of(request.amount(), request.currency()),
                request.description()
        );

        SubmitTransactionUseCase.TransactionResult result = submitTransactionUseCase.submitTransaction(command);

        TransactionResponseDto response = new TransactionResponseDto(
                result.transactionId(),
                result.referenceId(),
                result.debtorAccount(),
                result.creditorAccount(),
                result.amount(),
                result.currency(),
                result.status(),
                result.failureReason(),
                result.timestamp()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
