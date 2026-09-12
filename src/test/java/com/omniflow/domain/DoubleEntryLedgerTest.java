package com.omniflow.domain;

import com.omniflow.domain.exception.InsufficientFundsException;
import com.omniflow.domain.exception.LedgerImbalanceException;
import com.omniflow.domain.ledger.*;
import com.omniflow.domain.model.AccountId;
import com.omniflow.domain.model.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DoubleEntryLedgerTest {

    @Test
    @DisplayName("Should successfully create a balanced JournalEntry when debits equal credits")
    void shouldCreateBalancedJournalEntry() {
        AccountId source = AccountId.of("OMNI", "0001", "ACC-100");
        AccountId destination = AccountId.of("OMNI", "0001", "ACC-200");
        Money amount = Money.usd("250.00");

        PostingLeg debitLeg = PostingLeg.debit(source, amount, "Debit payment");
        PostingLeg creditLeg = PostingLeg.credit(destination, amount, "Credit payment");

        JournalEntry entry = new JournalEntry(
                "ENTRY-001",
                "REF-12345",
                "Payment settlement",
                List.of(debitLeg, creditLeg)
        );

        assertThat(entry.getEntryId()).isEqualTo("ENTRY-001");
        assertThat(entry.getLegs()).hasSize(2);
        assertThat(entry.getReferenceId()).isEqualTo("REF-12345");
    }

    @Test
    @DisplayName("Should throw LedgerImbalanceException when debits do not match credits")
    void shouldFailWhenImbalanced() {
        AccountId source = AccountId.of("OMNI", "0001", "ACC-100");
        AccountId destination = AccountId.of("OMNI", "0001", "ACC-200");

        PostingLeg debitLeg = PostingLeg.debit(source, Money.usd("100.00"), "Debit 100");
        PostingLeg creditLeg = PostingLeg.credit(destination, Money.usd("90.00"), "Credit 90");

        assertThatThrownBy(() -> new JournalEntry("ENTRY-BAD", "REF-BAD", "Imbalanced entry", List.of(debitLeg, creditLeg)))
                .isInstanceOf(LedgerImbalanceException.class)
                .hasMessageContaining("Ledger imbalance detected");
    }

    @Test
    @DisplayName("Should throw LedgerImbalanceException when less than 2 legs are provided")
    void shouldFailWhenLessThanTwoLegs() {
        AccountId source = AccountId.of("OMNI", "0001", "ACC-100");
        PostingLeg singleLeg = PostingLeg.debit(source, Money.usd("100.00"), "Single leg");

        assertThatThrownBy(() -> new JournalEntry("ENTRY-SINGLE", "REF-SINGLE", "Single leg", List.of(singleLeg)))
                .isInstanceOf(LedgerImbalanceException.class)
                .hasMessageContaining("Double-entry journal requires at least 2 posting legs");
    }

    @Test
    @DisplayName("Should correctly update Asset account balance: debit increases, credit decreases")
    void shouldUpdateAssetAccountBalance() {
        AccountId accId = AccountId.of("OMNI", "0001", "ASSET-1");
        LedgerAccount assetAccount = new LedgerAccount(
                accId, "Cash Vault", AccountType.ASSET, "USD", Money.usd("500.00"), false, 0L
        );

        // Debit increases Asset
        assetAccount.applyLeg(PostingLeg.debit(accId, Money.usd("200.00"), "Cash deposit"));
        assertThat(assetAccount.getBalance()).isEqualTo(Money.usd("700.00"));

        // Credit decreases Asset
        assetAccount.applyLeg(PostingLeg.credit(accId, Money.usd("300.00"), "Cash withdrawal"));
        assertThat(assetAccount.getBalance()).isEqualTo(Money.usd("400.00"));
    }

    @Test
    @DisplayName("Should throw InsufficientFundsException when debiting Asset account below zero if overdraft disabled")
    void shouldPreventOverdraftWhenNotAllowed() {
        AccountId accId = AccountId.of("OMNI", "0001", "ASSET-2");
        LedgerAccount assetAccount = new LedgerAccount(
                accId, "Checking Account", AccountType.ASSET, "USD", Money.usd("50.00"), false, 0L
        );

        PostingLeg withdrawLeg = PostingLeg.credit(accId, Money.usd("60.00"), "Overdraft attempt");

        assertThatThrownBy(() -> assetAccount.applyLeg(withdrawLeg))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("has insufficient funds");
    }

    @Test
    @DisplayName("Should throw CurrencyMismatchException when leg currency does not match account currency")
    void shouldRejectMismatchedLegCurrency() {
        AccountId accId = AccountId.of("OMNI", "0001", "USD-ACC");
        LedgerAccount assetAccount = new LedgerAccount(
                accId, "USD Account", AccountType.ASSET, "USD", Money.usd("100.00"), false, 0L
        );

        PostingLeg eurLeg = PostingLeg.debit(accId, Money.of("50.00", "EUR"), "EUR posting to USD account");

        assertThatThrownBy(() -> assetAccount.applyLeg(eurLeg))
                .isInstanceOf(com.omniflow.domain.exception.CurrencyMismatchException.class)
                .hasMessageContaining("does not match ledger account");
    }
}
