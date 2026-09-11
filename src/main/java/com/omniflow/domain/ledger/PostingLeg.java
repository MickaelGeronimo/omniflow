package com.omniflow.domain.ledger;

import com.omniflow.domain.model.AccountId;
import com.omniflow.domain.model.Money;

import java.util.Objects;

public record PostingLeg(AccountId accountId, PostingType type, Money amount, String description) {

    public PostingLeg {
        Objects.requireNonNull(accountId, "AccountId cannot be null");
        Objects.requireNonNull(type, "PostingType cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("PostingLeg amount must be strictly positive: " + amount);
        }
    }

    public static PostingLeg debit(AccountId accountId, Money amount, String description) {
        return new PostingLeg(accountId, PostingType.DEBIT, amount, description);
    }

    public static PostingLeg credit(AccountId accountId, Money amount, String description) {
        return new PostingLeg(accountId, PostingType.CREDIT, amount, description);
    }
}
