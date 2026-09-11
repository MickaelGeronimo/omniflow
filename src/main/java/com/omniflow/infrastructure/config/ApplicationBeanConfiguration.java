package com.omniflow.infrastructure.config;

import com.omniflow.application.port.in.SubmitTransactionUseCase;
import com.omniflow.application.port.out.IdempotencyStoragePort;
import com.omniflow.application.port.out.LedgerRepositoryPort;
import com.omniflow.application.port.out.OutboxRepositoryPort;
import com.omniflow.application.port.out.TransactionRepositoryPort;
import com.omniflow.application.service.FinancialOrchestratorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationBeanConfiguration {

    @Bean
    public SubmitTransactionUseCase submitTransactionUseCase(
            LedgerRepositoryPort ledgerRepository,
            TransactionRepositoryPort transactionRepository,
            OutboxRepositoryPort outboxRepository,
            IdempotencyStoragePort idempotencyStorage) {
        return new FinancialOrchestratorService(ledgerRepository, transactionRepository, outboxRepository, idempotencyStorage);
    }
}
