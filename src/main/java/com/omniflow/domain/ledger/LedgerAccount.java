package com.omniflow.domain.ledger;

import com.omniflow.domain.exception.CurrencyMismatchException;
import com.omniflow.domain.exception.InsufficientFundsException;
import com.omniflow.domain.model.AccountId;
import com.omniflow.domain.model.Money;

import java.util.Objects;

public class LedgerAccount {

    private final AccountId id;
    private final String name;
    private final AccountType type;
    private final String currency;
    private Money balance;
    private final boolean allowOverdraft;
    private long version;

    public LedgerAccount(AccountId id, String name, AccountType type, String currency, Money balance, boolean allowOverdraft, long version) {
        this.id = Objects.requireNonNull(id, "AccountId cannot be null");
        this.name = Objects.requireNonNull(name, "Name cannot be null");
        this.type = Objects.requireNonNull(type, "AccountType cannot be null");
        this.currency = Objects.requireNonNull(currency, "Currency cannot be null");
        this.balance = Objects.requireNonNull(balance, "Balance cannot be null");
        this.allowOverdraft = allowOverdraft;
        this.version = version;
    }

    public static LedgerAccount create(AccountId id, String name, AccountType type, String currency, boolean allowOverdraft) {
        return new LedgerAccount(id, name, type, currency, Money.zero(currency), allowOverdraft, 0L);
    }

    /**
     * Applies a posting leg according to standard accounting equation:
     * - ASSET / EXPENSE: Debits INCREASE balance, Credits DECREASE balance.
     * - LIABILITY / EQUITY / REVENUE: Credits INCREASE balance, Debits DECREASE balance.
     */
    public synchronized void applyLeg(PostingLeg leg) {
        Objects.requireNonNull(leg, "Posting leg cannot be null");
        if (!leg.accountId().equals(this.id)) {
            throw new IllegalArgumentException(
                    String.format("Leg account [%s] does not match ledger account [%s]", leg.accountId(), this.id));
        }
        if (!leg.amount().currencyCode().equalsIgnoreCase(this.currency)) {
            throw new CurrencyMismatchException(
                    String.format("Posting leg currency [%s] does not match ledger account [%s] currency [%s]",
                            leg.amount().currencyCode(), this.id, this.currency));
        }

        Money delta = leg.amount();
        Money newBalance;

        if (this.type == AccountType.ASSET || this.type == AccountType.EXPENSE) {
            newBalance = (leg.type() == PostingType.DEBIT) ? this.balance.add(delta) : this.balance.subtract(delta);
        } else {
            // LIABILITY, EQUITY, REVENUE
            newBalance = (leg.type() == PostingType.CREDIT) ? this.balance.add(delta) : this.balance.subtract(delta);
        }

        if (!allowOverdraft && newBalance.isNegative()) {
            throw new InsufficientFundsException(
                    String.format("Account [%s] has insufficient funds. Current: %s, Required debit: %s",
                            this.id, this.balance, delta));
        }

        this.balance = newBalance;
    }

    public AccountId getId() { return id; }
    public String getName() { return name; }
    public AccountType getType() { return type; }
    public String getCurrency() { return currency; }
    public Money getBalance() { return balance; }
    public boolean isAllowOverdraft() { return allowOverdraft; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
