package com.omniflow.domain;

import com.omniflow.domain.exception.OptimisticConcurrencyException;
import com.omniflow.domain.ledger.AccountType;
import com.omniflow.domain.ledger.LedgerAccount;
import com.omniflow.domain.ledger.PostingLeg;
import com.omniflow.domain.model.AccountId;
import com.omniflow.domain.model.Money;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.adapter.PostgresLedgerRepositoryAdapter;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.LedgerAccountJpaEntity;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataJournalEntryRepository;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataLedgerAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class LedgerOptimisticLockingConcurrencyTest {

    @Test
    @DisplayName("Should throw OptimisticConcurrencyException when two concurrent transactions attempt to update the same account version")
    void shouldDetectOptimisticLockCollisionBetweenConcurrentInstances() {
        SpringDataLedgerAccountRepository accountRepo = Mockito.mock(SpringDataLedgerAccountRepository.class);
        SpringDataJournalEntryRepository journalRepo = Mockito.mock(SpringDataJournalEntryRepository.class);
        PostgresLedgerRepositoryAdapter adapter = new PostgresLedgerRepositoryAdapter(accountRepo, journalRepo);

        AccountId accountId = AccountId.of("OMNI", "0001", "CONCURRENT-99");

        // Database currently holds version 1 (Instance A already committed an update)
        LedgerAccountJpaEntity dbEntity = new LedgerAccountJpaEntity(
                accountId.toString(),
                "Customer Account",
                AccountType.LIABILITY,
                "USD",
                new BigDecimal("500.00"),
                false
        );
        dbEntity.setVersion(1L); // Database has advanced to version 1

        when(accountRepo.findById(accountId.toString())).thenReturn(Optional.of(dbEntity));

        // Instance B read the account earlier when it was still version 0
        LedgerAccount staleInstanceB = new LedgerAccount(
                accountId,
                "Customer Account",
                AccountType.LIABILITY,
                "USD",
                Money.usd("400.00"),
                false,
                0L // Stale version 0!
        );
        staleInstanceB.applyLeg(PostingLeg.debit(accountId, Money.usd("100.00"), "Stale debit"));

        // When Instance B attempts to commit with stale version 0 against database version 1:
        assertThatThrownBy(() -> adapter.saveAccount(staleInstanceB))
                .isInstanceOf(OptimisticConcurrencyException.class)
                .hasMessageContaining("Optimistic lock mismatch on account")
                .hasMessageContaining("Expected version: 0, current database version: 1");
    }

    @Test
    @DisplayName("Should prove multithreaded concurrent race on identical account version results in exactly 1 commit and 1 collision")
    void shouldProveMultithreadedConcurrentRaceProducesOptimisticCollision() throws InterruptedException {
        AccountId accountId = AccountId.of("OMNI", "0001", "RACE-77");

        // Shared database entity protected by atomic state
        AtomicReference<LedgerAccountJpaEntity> dbStore = new AtomicReference<>(new LedgerAccountJpaEntity(
                accountId.toString(),
                "Shared Merchant",
                AccountType.LIABILITY,
                "USD",
                new BigDecimal("1000.00"),
                false
        ));
        dbStore.get().setVersion(0L);

        SpringDataLedgerAccountRepository accountRepo = Mockito.mock(SpringDataLedgerAccountRepository.class);
        SpringDataJournalEntryRepository journalRepo = Mockito.mock(SpringDataJournalEntryRepository.class);

        when(accountRepo.findById(accountId.toString())).thenAnswer(inv -> Optional.of(dbStore.get()));
        when(accountRepo.save(any())).thenAnswer(inv -> {
            LedgerAccountJpaEntity saved = inv.getArgument(0);
            saved.setVersion(saved.getVersion() + 1); // Database simulates JPA version bump on commit
            dbStore.set(saved);
            return saved;
        });

        PostgresLedgerRepositoryAdapter adapter = new PostgresLedgerRepositoryAdapter(accountRepo, journalRepo);

        // Both threads read account at initial version 0
        LedgerAccount thread1Account = new LedgerAccount(accountId, "Shared Merchant", AccountType.LIABILITY, "USD", Money.usd("1000.00"), false, 0L);
        LedgerAccount thread2Account = new LedgerAccount(accountId, "Shared Merchant", AccountType.LIABILITY, "USD", Money.usd("1000.00"), false, 0L);

        thread1Account.applyLeg(PostingLeg.credit(accountId, Money.usd("100.00"), "Credit from Thread 1"));
        thread2Account.applyLeg(PostingLeg.debit(accountId, Money.usd("50.00"), "Debit from Thread 2"));

        int concurrency = 2;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger collisionCounter = new AtomicInteger(0);

        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                synchronized (adapter) {
                    adapter.saveAccount(thread1Account);
                }
                successCounter.incrementAndGet();
            } catch (OptimisticConcurrencyException oce) {
                collisionCounter.incrementAndGet();
            } catch (Exception e) {
                // unexpected
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                synchronized (adapter) {
                    adapter.saveAccount(thread2Account);
                }
                successCounter.incrementAndGet();
            } catch (OptimisticConcurrencyException oce) {
                collisionCounter.incrementAndGet();
            } catch (Exception e) {
                // unexpected
            } finally {
                doneLatch.countDown();
            }
        });

        readyLatch.await();
        startLatch.countDown(); // Release both threads concurrently
        doneLatch.await();
        executor.shutdown();

        // Exactly one thread succeeded and one collided due to optimistic lock mismatch
        assertThat(successCounter.get()).isEqualTo(1);
        assertThat(collisionCounter.get()).isEqualTo(1);
    }
}
