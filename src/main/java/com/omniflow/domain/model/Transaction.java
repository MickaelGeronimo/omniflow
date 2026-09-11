package com.omniflow.domain.model;

import com.omniflow.domain.exception.InvalidTransactionStateException;

import java.time.Instant;
import java.util.Objects;

public class Transaction {

    private final TransactionId id;
    private final String referenceId;
    private final AccountId debtorAccount;
    private final AccountId creditorAccount;
    private final Money amount;
    private TransactionStatus status;
    private String failureReason;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    public Transaction(TransactionId id, String referenceId, AccountId debtorAccount, AccountId creditorAccount, Money amount) {
        this.id = Objects.requireNonNull(id, "TransactionId cannot be null");
        this.referenceId = Objects.requireNonNull(referenceId, "ReferenceId cannot be null");
        this.debtorAccount = Objects.requireNonNull(debtorAccount, "Debtor account cannot be null");
        this.creditorAccount = Objects.requireNonNull(creditorAccount, "Creditor account cannot be null");
        this.amount = Objects.requireNonNull(amount, "Amount cannot be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Transaction amount must be strictly positive: " + amount);
        }
        if (debtorAccount.equals(creditorAccount)) {
            throw new IllegalArgumentException("Debtor and creditor cannot be the same account: " + debtorAccount);
        }
        this.status = TransactionStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.version = 0L;
    }

    public Transaction(TransactionId id, String referenceId, AccountId debtorAccount, AccountId creditorAccount,
                       Money amount, TransactionStatus status, String failureReason, Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.referenceId = referenceId;
        this.debtorAccount = debtorAccount;
        this.creditorAccount = creditorAccount;
        this.amount = amount;
        this.status = status;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public void transitionTo(TransactionStatus next, String reason) {
        if (!this.status.canTransitionTo(next)) {
            throw new InvalidTransactionStateException(
                    String.format("Invalid state transition from %s to %s for tx [%s]", this.status, next, this.id));
        }
        this.status = next;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    public void markFundsReserved() {
        transitionTo(TransactionStatus.FUNDS_RESERVED, null);
    }

    public void markSettled() {
        transitionTo(TransactionStatus.SETTLED, null);
    }

    public void markRejectedInsufficientFunds(String reason) {
        transitionTo(TransactionStatus.REJECTED_INSUFFICIENT_FUNDS, reason);
    }

    public void markRejectedFraud(String reason) {
        transitionTo(TransactionStatus.REJECTED_FRAUD, reason);
    }

    public void markRejectedDeadLetter(String reason) {
        transitionTo(TransactionStatus.REJECTED_DEAD_LETTER, reason);
    }

    public void markCompensated(String reason) {
        transitionTo(TransactionStatus.COMPENSATED, reason);
    }

    public TransactionId getId() { return id; }
    public String getReferenceId() { return referenceId; }
    public AccountId getDebtorAccount() { return debtorAccount; }
    public AccountId getCreditorAccount() { return creditorAccount; }
    public Money getAmount() { return amount; }
    public TransactionStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
