package com.omniflow.domain.ledger;

import com.omniflow.domain.exception.LedgerImbalanceException;
import com.omniflow.domain.model.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable accounting batch enforcing double-entry zero-sum invariants:
 * Sum(Debits) == Sum(Credits).
 */
public class JournalEntry {

    private final String entryId;
    private final String referenceId;
    private final Instant timestamp;
    private final String memo;
    private final List<PostingLeg> legs;

    public JournalEntry(String entryId, String referenceId, Instant timestamp, String memo, List<PostingLeg> legs) {
        this.entryId = Objects.requireNonNull(entryId, "entryId cannot be null");
        this.referenceId = Objects.requireNonNull(referenceId, "referenceId cannot be null");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp cannot be null");
        this.memo = Objects.requireNonNull(memo, "memo cannot be null");
        this.legs = List.copyOf(Objects.requireNonNull(legs, "legs cannot be null"));

        validateZeroSumBalance();
    }

    public JournalEntry(String entryId, String referenceId, String memo, List<PostingLeg> legs) {
        this(entryId, referenceId, Instant.now(), memo, legs);
    }

    private void validateZeroSumBalance() {
        if (legs.size() < 2) {
            throw new LedgerImbalanceException("Double-entry journal requires at least 2 posting legs, found: " + legs.size());
        }

        String currency = legs.get(0).amount().currencyCode();
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        for (PostingLeg leg : legs) {
            if (!leg.amount().currencyCode().equalsIgnoreCase(currency)) {
                throw new LedgerImbalanceException("Multi-currency posting legs in single journal entry not permitted");
            }
            if (leg.type() == PostingType.DEBIT) {
                totalDebits = totalDebits.add(leg.amount().amount());
            } else {
                totalCredits = totalCredits.add(leg.amount().amount());
            }
        }

        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new LedgerImbalanceException(
                    String.format("Ledger imbalance detected: Total Debits [%s] != Total Credits [%s] %s",
                            totalDebits, totalCredits, currency));
        }
    }

    public String getEntryId() { return entryId; }
    public String getReferenceId() { return referenceId; }
    public Instant getTimestamp() { return timestamp; }
    public String getMemo() { return memo; }
    public List<PostingLeg> getLegs() { return Collections.unmodifiableList(legs); }
}
