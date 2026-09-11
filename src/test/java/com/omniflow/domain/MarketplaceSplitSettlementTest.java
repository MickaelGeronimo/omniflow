package com.omniflow.domain;

import com.omniflow.application.port.in.SubmitTransactionUseCase;
import com.omniflow.application.port.out.*;
import com.omniflow.application.service.FinancialOrchestratorService;
import com.omniflow.domain.ledger.AccountType;
import com.omniflow.domain.ledger.LedgerAccount;
import com.omniflow.domain.model.AccountId;
import com.omniflow.domain.model.Money;
import com.omniflow.domain.model.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MarketplaceSplitSettlementTest {

    private LedgerRepositoryPort ledgerRepository;
    private TransactionRepositoryPort transactionRepository;
    private OutboxRepositoryPort outboxRepository;
    private IdempotencyStoragePort idempotencyStorage;
    private FinancialOrchestratorService orchestratorService;

    private final AccountId buyer = AccountId.of("OMNI", "0001", "BUYER-101");
    private final AccountId merchant = AccountId.of("OMNI", "0001", "MERCHANT-202");
    private final AccountId platformFee = AccountId.of("OMNI", "0001", "PLATFORM-FEE");
    private final AccountId chargebackReserve = AccountId.of("OMNI", "0001", "ESCROW-RESERVE");

    private LedgerAccount buyerAcc;
    private LedgerAccount merchantAcc;
    private LedgerAccount feeAcc;
    private LedgerAccount reserveAcc;

    @BeforeEach
    void setUp() {
        ledgerRepository = Mockito.mock(LedgerRepositoryPort.class);
        transactionRepository = Mockito.mock(TransactionRepositoryPort.class);
        outboxRepository = Mockito.mock(OutboxRepositoryPort.class);
        idempotencyStorage = Mockito.mock(IdempotencyStoragePort.class);

        when(idempotencyStorage.tryAcquire(any(), any()))
                .thenReturn(new IdempotencyStoragePort.AcquireResult(IdempotencyStoragePort.LockStatus.ACQUIRED, null));

        buyerAcc = new LedgerAccount(buyer, "Buyer Account", AccountType.LIABILITY, "USD", Money.usd("1000.00"), false, 0L);
        merchantAcc = new LedgerAccount(merchant, "Merchant Payout Account", AccountType.LIABILITY, "USD", Money.zero("USD"), true, 0L);
        feeAcc = new LedgerAccount(platformFee, "Platform Take-Rate Revenue", AccountType.REVENUE, "USD", Money.zero("USD"), true, 0L);
        reserveAcc = new LedgerAccount(chargebackReserve, "Chargeback Escrow Reserve", AccountType.LIABILITY, "USD", Money.zero("USD"), true, 0L);

        when(ledgerRepository.findAccountById(buyer)).thenReturn(Optional.of(buyerAcc));
        when(ledgerRepository.findAccountById(merchant)).thenReturn(Optional.of(merchantAcc));
        when(ledgerRepository.findAccountById(platformFee)).thenReturn(Optional.of(feeAcc));
        when(ledgerRepository.findAccountById(chargebackReserve)).thenReturn(Optional.of(reserveAcc));

        orchestratorService = new FinancialOrchestratorService(
                ledgerRepository, transactionRepository, outboxRepository, idempotencyStorage
        );
    }

    @Test
    @DisplayName("Should execute 4-leg marketplace split settlement with exact mathematical zero-sum balance")
    void shouldExecuteMarketplaceFourLegSplit() {
        // Buyer pays $1,000.00
        // Platform Fee: $50.00 (5%)
        // Chargeback Reserve: $20.00 (2%)
        // Net Merchant Payout: $930.00 (93%)
        SubmitTransactionUseCase.SplitAllocation split = new SubmitTransactionUseCase.SplitAllocation(
                platformFee, Money.usd("50.00"),
                chargebackReserve, Money.usd("20.00")
        );

        SubmitTransactionUseCase.SubmitCommand command = new SubmitTransactionUseCase.SubmitCommand(
                "IDEMP-SPLIT-001",
                "ORD-MKT-9912",
                buyer,
                merchant,
                Money.usd("1000.00"),
                "Marketplace order checkout",
                split
        );

        SubmitTransactionUseCase.TransactionResult result = orchestratorService.submitTransaction(command);

        assertThat(result.status()).isEqualTo(TransactionStatus.FUNDS_RESERVED);
        assertThat(result.amount()).isEqualTo("1000.0000");

        // Debtor asset reduced from $1,000.00 to $0.00
        assertThat(buyerAcc.getBalance()).isEqualTo(Money.zero("USD"));

        // Merchant received net $930.00
        assertThat(merchantAcc.getBalance()).isEqualTo(Money.usd("930.00"));

        // Platform earned $50.00 fee
        assertThat(feeAcc.getBalance()).isEqualTo(Money.usd("50.00"));

        // Reserve hold holds $20.00
        assertThat(reserveAcc.getBalance()).isEqualTo(Money.usd("20.00"));

        // Zero-sum invariant: 930 + 50 + 20 == 1000
        Money totalCredits = merchantAcc.getBalance().add(feeAcc.getBalance()).add(reserveAcc.getBalance());
        assertThat(totalCredits).isEqualTo(Money.usd("1000.00"));
    }
}
