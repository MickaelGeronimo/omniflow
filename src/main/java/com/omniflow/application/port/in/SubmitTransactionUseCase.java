package com.omniflow.application.port.in;

import com.omniflow.domain.model.AccountId;
import com.omniflow.domain.model.Money;
import com.omniflow.domain.model.TransactionStatus;

import java.time.Instant;

public interface SubmitTransactionUseCase {

    record SplitAllocation(
            AccountId feeAccount,
            Money feeAmount,
            AccountId reserveAccount,
            Money reserveAmount
    ) {}

    record SubmitCommand(
            String idempotencyKey,
            String referenceId,
            AccountId debtorAccount,
            AccountId creditorAccount,
            Money amount,
            String description,
            SplitAllocation splitAllocation
    ) {
        public SubmitCommand(String idempotencyKey, String referenceId, AccountId debtorAccount, AccountId creditorAccount, Money amount, String description) {
            this(idempotencyKey, referenceId, debtorAccount, creditorAccount, amount, description, null);
        }
    }

    record TransactionResult(
            String transactionId,
            String referenceId,
            String debtorAccount,
            String creditorAccount,
            String amount,
            String currency,
            TransactionStatus status,
            String failureReason,
            Instant timestamp
    ) {}

    TransactionResult submitTransaction(SubmitCommand command);
}
