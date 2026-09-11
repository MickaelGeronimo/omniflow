package com.omniflow.infrastructure.adapter.out.persistence.postgres.entity;

import com.omniflow.domain.ledger.PostingType;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "posting_legs")
public class PostingLegJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "leg_id")
    private Long legId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id")
    private JournalEntryJpaEntity journalEntry;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_type", nullable = false, length = 8)
    private PostingType postingType;

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "description")
    private String description;

    public PostingLegJpaEntity() {}

    public PostingLegJpaEntity(JournalEntryJpaEntity journalEntry, String accountId, PostingType postingType,
                               BigDecimal amount, String currency, String description) {
        this.journalEntry = journalEntry;
        this.accountId = accountId;
        this.postingType = postingType;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
    }

    public Long getLegId() { return legId; }
    public JournalEntryJpaEntity getJournalEntry() { return journalEntry; }
    public void setJournalEntry(JournalEntryJpaEntity journalEntry) { this.journalEntry = journalEntry; }
    public String getAccountId() { return accountId; }
    public PostingType getPostingType() { return postingType; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
}
