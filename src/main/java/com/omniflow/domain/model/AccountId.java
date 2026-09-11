package com.omniflow.domain.model;

import java.util.Objects;

public record AccountId(String bankCode, String branch, String number) {

    public AccountId {
        Objects.requireNonNull(bankCode, "Bank code cannot be null");
        Objects.requireNonNull(number, "Account number cannot be null");
        if (bankCode.isBlank() || number.isBlank()) {
            throw new IllegalArgumentException("Bank code and account number cannot be blank");
        }
    }

    public static AccountId of(String bankCode, String branch, String number) {
        return new AccountId(bankCode, branch, number);
    }

    public static AccountId parse(String raw) {
        Objects.requireNonNull(raw, "Account string cannot be null");
        String[] parts = raw.split(":");
        if (parts.length == 3) {
            return new AccountId(parts[0], parts[1], parts[2]);
        } else if (parts.length == 2) {
            return new AccountId(parts[0], "0001", parts[1]);
        } else {
            return new AccountId("OMNI", "0001", raw);
        }
    }

    @Override
    public String toString() {
        return bankCode + ":" + (branch != null ? branch + ":" : "") + number;
    }
}
