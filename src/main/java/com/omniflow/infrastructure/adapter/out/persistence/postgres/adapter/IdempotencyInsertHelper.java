package com.omniflow.infrastructure.adapter.out.persistence.postgres.adapter;

import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.IdempotencyKeyJpaEntity;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataIdempotencyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class IdempotencyInsertHelper {

    private final SpringDataIdempotencyRepository repo;

    public IdempotencyInsertHelper(SpringDataIdempotencyRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryInsertInFlight(String key, String fingerprint) {
        try {
            IdempotencyKeyJpaEntity entity = new IdempotencyKeyJpaEntity(
                    key,
                    fingerprint,
                    "IN_FLIGHT",
                    null,
                    Instant.now(),
                    Instant.now().plus(2, ChronoUnit.MINUTES)
            );
            repo.saveAndFlush(entity);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
