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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
}
