package com.omniflow.application.port.out;

import com.omniflow.application.port.in.SubmitTransactionUseCase.TransactionResult;

import java.util.Optional;

public interface IdempotencyStoragePort {

    enum LockStatus {
        ACQUIRED,
        ALREADY_COMPLETED,
        CONFLICTING_PAYLOAD,
        CONCURRENT_EXECUTION
    }

    record AcquireResult(LockStatus status, TransactionResult cachedResult) {}

    AcquireResult tryAcquire(String idempotencyKey, String requestFingerprint);

    void markCompleted(String idempotencyKey, TransactionResult result);

    void releaseLock(String idempotencyKey);
}
