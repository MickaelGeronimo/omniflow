package com.omniflow.domain.model;

import java.util.EnumSet;

/**
 * Deterministic Financial State Machine states.
 */
public enum TransactionStatus {
    PENDING,
    FUNDS_RESERVED,
    SETTLED,
    REJECTED_INSUFFICIENT_FUNDS,
    REJECTED_FRAUD,
    REJECTED_DEAD_LETTER,
    COMPENSATED;

    public boolean isTerminal() {
        return this == SETTLED 
            || this == REJECTED_INSUFFICIENT_FUNDS 
            || this == REJECTED_FRAUD 
            || this == REJECTED_DEAD_LETTER 
            || this == COMPENSATED;
    }

    public boolean canTransitionTo(TransactionStatus next) {
        if (this == next) return true;
        return switch (this) {
            case PENDING -> EnumSet.of(FUNDS_RESERVED, REJECTED_INSUFFICIENT_FUNDS, REJECTED_FRAUD).contains(next);
            case FUNDS_RESERVED -> EnumSet.of(SETTLED, REJECTED_DEAD_LETTER, COMPENSATED).contains(next);
            case SETTLED, REJECTED_INSUFFICIENT_FUNDS, REJECTED_FRAUD, REJECTED_DEAD_LETTER, COMPENSATED -> false;
        };
    }
}
