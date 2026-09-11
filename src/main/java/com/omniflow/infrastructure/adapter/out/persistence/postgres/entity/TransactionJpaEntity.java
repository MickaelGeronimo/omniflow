package com.omniflow.infrastructure.adapter.out.persistence.postgres.entity;

import com.omniflow.domain.model.TransactionStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transactions")
public class TransactionJpaEntity {

    @Id
    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "reference_id", nullable = false, unique = true, length = 64)
    private String referenceId;

    @Column(name = "debtor_account", nullable = false, length = 64)
    private String debtorAccount;

    @Column(name = "creditor_account", nullable = false, length = 64)
    private String creditorAccount;

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TransactionStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    public TransactionJpaEntity() {}

    public TransactionJpaEntity(String transactionId, String referenceId, String debtorAccount, String creditorAccount,
                                BigDecimal amount, String currency, TransactionStatus status, String failureReason,
                                Instant createdAt, Instant updatedAt, Long version) {
        this.transactionId = transactionId;
        this.referenceId = referenceId;
        this.debtorAccount = debtorAccount;
        this.creditorAccount = creditorAccount;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public String getTransactionId() { return transactionId; }
    public String getReferenceId() { return referenceId; }
    public String getDebtorAccount() { return debtorAccount; }
    public String getCreditorAccount() { return creditorAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
}
