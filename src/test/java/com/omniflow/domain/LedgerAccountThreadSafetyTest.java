package com.omniflow.domain;

import com.omniflow.domain.ledger.AccountType;
import com.omniflow.domain.ledger.LedgerAccount;
import com.omniflow.domain.ledger.PostingLeg;
import com.omniflow.domain.model.AccountId;
import com.omniflow.domain.model.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerAccountThreadSafetyTest {

    @Test
    @DisplayName("Should maintain exact balance integrity during high-concurrency race condition on in-memory domain aggregate")
    void shouldMaintainBalanceIntegrityUnderConcurrency() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AccountId accountId = AccountId.of("OMNI", "0001", "RACE-ACC");
        LedgerAccount account = new LedgerAccount(
                accountId, "High Concurrency Account", AccountType.ASSET, "USD", Money.zero("USD"), true, 0L
        );

        AtomicInteger successfulDeposits = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    account.applyLeg(PostingLeg.debit(accountId, Money.usd("10.00"), "Concurrent Deposit"));
                    successfulDeposits.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successfulDeposits.get()).isEqualTo(threadCount);
        assertThat(account.getBalance()).isEqualTo(Money.usd("500.00"));
    }
}
