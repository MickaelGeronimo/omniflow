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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
    @DisplayName("Should prove real database optimistic locking collision with two simultaneous open transactions")
    void shouldDetectRealDatabaseOptimisticLockCollision() throws InterruptedException {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String accountId = "OMNI:0001:REAL-SIMULTANEOUS-LOCK";

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
            return accountRepo.saveAndFlush(initial);
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch bothReadLatch = new CountDownLatch(2);
        CountDownLatch commitGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        java.util.concurrent.atomic.AtomicInteger successCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger collisionCounter = new java.util.concurrent.atomic.AtomicInteger(0);

        // Simultaneous Transaction A
        executor.submit(() -> {
            try {
                txTemplate.execute(status -> {
                    LedgerAccountJpaEntity acc = accountRepo.findById(accountId).orElseThrow();
                    bothReadLatch.countDown();
                    try {
                        commitGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    acc.setBalance(new BigDecimal("900.00"));
                    accountRepo.saveAndFlush(acc);
                    return null;
                });
                successCounter.incrementAndGet();
            } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                collisionCounter.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        // Simultaneous Transaction B
        executor.submit(() -> {
            try {
                txTemplate.execute(status -> {
                    LedgerAccountJpaEntity acc = accountRepo.findById(accountId).orElseThrow();
                    bothReadLatch.countDown();
                    try {
                        commitGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    acc.setBalance(new BigDecimal("850.00"));
                    accountRepo.saveAndFlush(acc);
                    return null;
                });
                successCounter.incrementAndGet();
            } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                collisionCounter.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        bothReadLatch.await(); // Guarantees both transactions have read version 0 simultaneously
        commitGate.countDown(); // Releases both transactions to commit concurrently
        doneLatch.await();
        executor.shutdown();

        // Exactly one transaction succeeds (version 0 -> 1) and the second collides with OptimisticLockingFailureException
        assertThat(successCounter.get()).isEqualTo(1);
        assertThat(collisionCounter.get()).isEqualTo(1);

        LedgerAccountJpaEntity finalAccount = txTemplate.execute(status -> accountRepo.findById(accountId).orElseThrow());
        assertThat(finalAccount.getVersion()).isEqualTo(1L);
    }
}
