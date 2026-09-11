package com.omniflow.domain.model;

import java.util.Objects;
import java.util.UUID;

public record TransactionId(String value) {

    public TransactionId {
        Objects.requireNonNull(value, "TransactionId cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("TransactionId cannot be blank");
        }
    }

    public static TransactionId generate() {
        return new TransactionId("TX-" + UUID.randomUUID());
    }

    public static TransactionId of(String value) {
        return new TransactionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
