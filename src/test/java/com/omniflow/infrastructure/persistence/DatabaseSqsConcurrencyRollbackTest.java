package com.omniflow.infrastructure.persistence;

import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.LedgerAccountJpaEntity;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.ProcessedSettlementEventJpaEntity;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataLedgerAccountRepository;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataProcessedEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class DatabaseSqsConcurrencyRollbackTest {

    @Autowired
    private SpringDataProcessedEventRepository processedEventRepo;

    @Autowired
    private SpringDataLedgerAccountRepository accountRepo;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Should prove rollback of processed_event reservation token when settlement transaction fails")
    void shouldRollbackProcessedEventTokenWhenSettlementFails() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String eventId = "EVT-ROLLBACK-TEST-1";
        String txId = "tx-fail-100";

        // Transaction 1: Inserts reservation token, but encounters a settlement failure midway and aborts
        assertThatThrownBy(() -> txTemplate.execute(status -> {
            processedEventRepo.saveAndFlush(new ProcessedSettlementEventJpaEntity(eventId, txId, "CORR-1", Instant.now()));

            // Simulate domain/network failure before transaction commit
            throw new RuntimeException("Simulated Settlement Failure during account balance update");
        })).isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Simulated Settlement Failure");

        // After rollback: the token must NOT exist in the database, allowing subsequent SQS redrive/retry
        assertThat(processedEventRepo.existsById(eventId)).isFalse();

        // Transaction 2 (SQS redrive retry): The redelivered message can successfully acquire the token and settle
        ProcessedSettlementEventJpaEntity retryResult = txTemplate.execute(status ->
                processedEventRepo.saveAndFlush(new ProcessedSettlementEventJpaEntity(eventId, txId, "CORR-1-REDRIVE", Instant.now()))
        );

        assertThat(retryResult).isNotNull();
        assertThat(processedEventRepo.existsById(eventId)).isTrue();
    }

    @Test
    @DisplayName("Should prove real database optimistic locking collision when concurrent transactions update the same account version")
    void shouldDetectRealDatabaseOptimisticLockCollision() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String accountId = "OMNI:0001:REAL-LOCK-1";

        // Seed initial account row at version 0
        txTemplate.execute(status -> {
            LedgerAccountJpaEntity initial = new LedgerAccountJpaEntity(
                    accountId,
                    "Merchant Pool",
                    com.omniflow.domain.ledger.AccountType.LIABILITY,
                    "USD",
                    new BigDecimal("1000.00"),
                    false
            );
            return accountRepo.save(initial);
        });

        // Transaction 1 reads account at version 0
        LedgerAccountJpaEntity instance1 = txTemplate.execute(status -> accountRepo.findById(accountId).orElseThrow());
        assertThat(instance1.getVersion()).isEqualTo(0L);

        // Transaction 2 reads account at version 0
        LedgerAccountJpaEntity instance2 = txTemplate.execute(status -> accountRepo.findById(accountId).orElseThrow());
        assertThat(instance2.getVersion()).isEqualTo(0L);

        // Transaction 1 commits update -> database version advances to 1
        txTemplate.execute(status -> {
            instance1.setBalance(new BigDecimal("900.00"));
            return accountRepo.saveAndFlush(instance1);
        });

        LedgerAccountJpaEntity updated = txTemplate.execute(status -> accountRepo.findById(accountId).orElseThrow());
        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(updated.getBalance()).isEqualByComparingTo("900.00");

        // Transaction 2 attempts to commit with stale version 0 against database version 1
        assertThatThrownBy(() -> txTemplate.execute(status -> {
            instance2.setBalance(new BigDecimal("950.00"));
            return accountRepo.saveAndFlush(instance2);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
