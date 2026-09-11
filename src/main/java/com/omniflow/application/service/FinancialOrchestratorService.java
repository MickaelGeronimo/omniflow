package com.omniflow.application.service;

import com.omniflow.application.port.in.SubmitTransactionUseCase;
import com.omniflow.application.port.out.*;
import com.omniflow.domain.exception.ConflictingPayloadException;
import com.omniflow.domain.ledger.JournalEntry;
import com.omniflow.domain.ledger.LedgerAccount;
import com.omniflow.domain.ledger.PostingLeg;
import com.omniflow.domain.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FinancialOrchestratorService implements SubmitTransactionUseCase {

    public static final AccountId SETTLEMENT_TRANSIT_ACCOUNT = AccountId.of("CLEARING", "0001", "TRANSIT-999");

    private final LedgerRepositoryPort ledgerRepository;
    private final TransactionRepositoryPort transactionRepository;
    private final OutboxRepositoryPort outboxRepository;
    private final IdempotencyStoragePort idempotencyStorage;

    public FinancialOrchestratorService(
            LedgerRepositoryPort ledgerRepository,
            TransactionRepositoryPort transactionRepository,
            OutboxRepositoryPort outboxRepository,
            IdempotencyStoragePort idempotencyStorage) {
        this.ledgerRepository = Objects.requireNonNull(ledgerRepository, "ledgerRepository cannot be null");
        this.transactionRepository = Objects.requireNonNull(transactionRepository, "transactionRepository cannot be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository cannot be null");
        this.idempotencyStorage = Objects.requireNonNull(idempotencyStorage, "idempotencyStorage cannot be null");
    }

    @Override
    public TransactionResult submitTransaction(SubmitCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        // 1. Idempotency Check & Fingerprinting
        String fingerprint = computeFingerprint(command);
        var acquireResult = idempotencyStorage.tryAcquire(command.idempotencyKey(), fingerprint);

        if (acquireResult.status() == IdempotencyStoragePort.LockStatus.ALREADY_COMPLETED) {
            return acquireResult.cachedResult();
        }
        if (acquireResult.status() == IdempotencyStoragePort.LockStatus.CONFLICTING_PAYLOAD) {
            throw new ConflictingPayloadException(
                    "Idempotency key [" + command.idempotencyKey() + "] previously used with different payload");
        }
        if (acquireResult.status() == IdempotencyStoragePort.LockStatus.CONCURRENT_EXECUTION) {
            throw new IllegalStateException(
                    "Transaction with idempotency key [" + command.idempotencyKey() + "] is currently in-flight");
        }

        try {
            TransactionResult result = executeAtomicReservation(command);
            idempotencyStorage.markCompleted(command.idempotencyKey(), result);
            return result;
        } catch (Exception ex) {
            idempotencyStorage.releaseLock(command.idempotencyKey());
            throw ex;
        }
    }

    private TransactionResult executeAtomicReservation(SubmitCommand command) {
        TransactionId txId = TransactionId.generate();
        Transaction transaction = new Transaction(
                txId,
                command.referenceId(),
                command.debtorAccount(),
                command.creditorAccount(),
                command.amount()
        );

        LedgerAccount debtor = ledgerRepository.findAccountById(command.debtorAccount())
                .orElseThrow(() -> new IllegalArgumentException("Debtor account not found: " + command.debtorAccount()));

        JournalEntry journalEntry;
        List<LedgerAccount> modifiedAccounts = new ArrayList<>();
        modifiedAccounts.add(debtor);

        if (command.splitAllocation() != null) {
            // Marketplace 4-Leg Split Settlement:
            // Leg 1: Debit Buyer / Acquirer (Gross amount)
            // Leg 2: Credit Merchant Net Payout (Gross - Fee - Reserve)
            // Leg 3: Credit Platform Take-rate Fee Account
            // Leg 4: Credit Chargeback Risk Escrow Reserve Account
            SplitAllocation split = command.splitAllocation();
            Money fee = split.feeAmount();
            Money reserve = split.reserveAmount();
            Money netPayout = command.amount().subtract(fee).subtract(reserve);

            LedgerAccount merchant = ledgerRepository.findAccountById(command.creditorAccount())
                    .orElseThrow(() -> new IllegalArgumentException("Merchant account not found: " + command.creditorAccount()));
            LedgerAccount feeAcc = ledgerRepository.findAccountById(split.feeAccount())
                    .orElseThrow(() -> new IllegalArgumentException("Fee account not found: " + split.feeAccount()));
            LedgerAccount reserveAcc = ledgerRepository.findAccountById(split.reserveAccount())
                    .orElseThrow(() -> new IllegalArgumentException("Reserve escrow account not found: " + split.reserveAccount()));

            PostingLeg leg1 = PostingLeg.debit(debtor.getId(), command.amount(), "Gross settlement debit for " + txId);
            PostingLeg leg2 = PostingLeg.credit(merchant.getId(), netPayout, "Net merchant payout for " + txId);
            PostingLeg leg3 = PostingLeg.credit(feeAcc.getId(), fee, "Platform take-rate fee for " + txId);
            PostingLeg leg4 = PostingLeg.credit(reserveAcc.getId(), reserve, "Chargeback risk escrow for " + txId);

            debtor.applyLeg(leg1);
            merchant.applyLeg(leg2);
            feeAcc.applyLeg(leg3);
            reserveAcc.applyLeg(leg4);

            modifiedAccounts.add(merchant);
            modifiedAccounts.add(feeAcc);
            modifiedAccounts.add(reserveAcc);

            journalEntry = new JournalEntry(
                    "SPLIT-" + txId.value(),
                    command.referenceId(),
                    "Marketplace 4-leg split settlement for " + txId,
                    List.of(leg1, leg2, leg3, leg4)
            );
        } else {
            // Standard 2-Leg Transit Hold:
            LedgerAccount transit = ledgerRepository.findAccountById(SETTLEMENT_TRANSIT_ACCOUNT)
                    .orElseThrow(() -> new IllegalStateException("Transit settlement account not configured"));

            PostingLeg leg1 = PostingLeg.debit(debtor.getId(), command.amount(), "Reservation for tx " + txId);
            PostingLeg leg2 = PostingLeg.credit(transit.getId(), command.amount(), "Transit hold for tx " + txId);

            debtor.applyLeg(leg1);
            transit.applyLeg(leg2);
            modifiedAccounts.add(transit);

            journalEntry = new JournalEntry(
                    "HOLD-" + txId.value(),
                    command.referenceId(),
                    "Funds reservation for tx " + txId,
                    List.of(leg1, leg2)
            );
        }

        transaction.markFundsReserved();

        // Atomic Unit of Work: save all modified accounts, journal entry, transaction, and outbox event
        for (LedgerAccount acc : modifiedAccounts) {
            ledgerRepository.saveAccount(acc);
        }
        ledgerRepository.saveJournalEntry(journalEntry);
        transactionRepository.save(transaction);

        // Transactional Outbox Pattern: Event committed atomically with the financial ledger
        outboxRepository.saveEvent(
                "Transaction",
                txId.value(),
                "TRANSACTION_FUNDS_RESERVED",
                Map.of(
                        "transactionId", txId.value(),
                        "referenceId", command.referenceId(),
                        "debtorAccount", command.debtorAccount().toString(),
                        "creditorAccount", command.creditorAccount().toString(),
                        "amount", command.amount().amount().toPlainString(),
                        "currency", command.amount().currencyCode(),
                        "status", transaction.getStatus().name(),
                        "timestamp", Instant.now().toString()
                )
        );

        return new TransactionResult(
                txId.value(),
                transaction.getReferenceId(),
                transaction.getDebtorAccount().toString(),
                transaction.getCreditorAccount().toString(),
                transaction.getAmount().amount().toPlainString(),
                transaction.getAmount().currencyCode(),
                transaction.getStatus(),
                transaction.getFailureReason(),
                transaction.getCreatedAt()
        );
    }

    private String computeFingerprint(SubmitCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = command.referenceId() + "|" +
                         command.debtorAccount().toString() + "|" +
                         command.creditorAccount().toString() + "|" +
                         command.amount().amount().toPlainString() + "|" +
                         command.amount().currencyCode() + "|" +
                         (command.description() != null ? command.description() : "");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 digest unavailable", e);
        }
    }
}
