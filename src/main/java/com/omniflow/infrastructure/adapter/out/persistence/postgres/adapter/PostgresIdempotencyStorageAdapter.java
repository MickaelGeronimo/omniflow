package com.omniflow.infrastructure.adapter.out.persistence.postgres.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniflow.application.port.in.SubmitTransactionUseCase.TransactionResult;
import com.omniflow.application.port.out.IdempotencyStoragePort;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.IdempotencyKeyJpaEntity;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataIdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

@Repository
public class PostgresIdempotencyStorageAdapter implements IdempotencyStoragePort {

    private static final Logger log = LoggerFactory.getLogger(PostgresIdempotencyStorageAdapter.class);

    private final SpringDataIdempotencyRepository repo;
    private final IdempotencyInsertHelper insertHelper;
    private final ObjectMapper objectMapper;

    public PostgresIdempotencyStorageAdapter(
            SpringDataIdempotencyRepository repo,
            IdempotencyInsertHelper insertHelper,
            ObjectMapper objectMapper) {
        this.repo = repo;
        this.insertHelper = insertHelper;
        this.objectMapper = objectMapper;
    }

    @Override
    public AcquireResult tryAcquire(String idempotencyKey, String requestFingerprint) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint cannot be null");

        if (repo.existsById(idempotencyKey)) {
            return handleExistingKey(idempotencyKey, requestFingerprint);
        }

        boolean acquired = insertHelper.tryInsertInFlight(idempotencyKey, requestFingerprint);
        if (acquired) {
            log.debug("[IDEMP] Acquired distributed lock for key [{}]", idempotencyKey);
            return new AcquireResult(LockStatus.ACQUIRED, null);
        } else {
            return handleExistingKey(idempotencyKey, requestFingerprint);
        }
    }

    private AcquireResult handleExistingKey(String key, String fingerprint) {
        long deadline = System.currentTimeMillis() + 5000;

        while (System.currentTimeMillis() < deadline) {
            Optional<IdempotencyKeyJpaEntity> opt = repo.findById(key);
            if (opt.isEmpty()) {
                return tryAcquire(key, fingerprint);
            }

            IdempotencyKeyJpaEntity entity = opt.get();
            if (!entity.getRequestFingerprint().equals(fingerprint)) {
                return new AcquireResult(LockStatus.CONFLICTING_PAYLOAD, null);
            }

            if ("COMPLETED".equals(entity.getStatus())) {
                TransactionResult cached = deserializeResult(entity.getResponsePayload());
                return new AcquireResult(LockStatus.ALREADY_COMPLETED, cached);
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new AcquireResult(LockStatus.CONCURRENT_EXECUTION, null);
            }
        }

        return new AcquireResult(LockStatus.CONCURRENT_EXECUTION, null);
    }

    @Override
    @Transactional
    public void markCompleted(String idempotencyKey, TransactionResult result) {
        repo.findById(idempotencyKey).ifPresent(entity -> {
            try {
                entity.setStatus("COMPLETED");
                entity.setResponsePayload(objectMapper.writeValueAsString(result));
                repo.saveAndFlush(entity);
            } catch (Exception e) {
                log.error("Failed to serialize transaction result for idempotency key [{}]", idempotencyKey, e);
            }
        });
    }

    @Override
    @Transactional
    public void releaseLock(String idempotencyKey) {
        repo.deleteById(idempotencyKey);
    }

    private TransactionResult deserializeResult(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, TransactionResult.class);
        } catch (Exception e) {
            log.error("Failed to deserialize cached idempotency payload", e);
            return null;
        }
    }
}
