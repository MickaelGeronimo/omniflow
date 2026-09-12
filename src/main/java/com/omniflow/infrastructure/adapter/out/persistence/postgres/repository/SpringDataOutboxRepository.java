package com.omniflow.infrastructure.adapter.out.persistence.postgres.repository;

import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.OutboxEventJpaEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SpringDataOutboxRepository extends JpaRepository<OutboxEventJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")}) // -2 is SKIP LOCKED in Hibernate
    @Query("""
        SELECT e FROM OutboxEventJpaEntity e
        WHERE (e.status = 'PENDING'
           OR (e.status = 'PROCESSING' AND e.lockedAt < :leaseCutoff)
           OR (e.status = 'FAILED' AND e.retryCount < :maxRetries AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :now)))
        ORDER BY e.createdAt ASC
    """)
    List<OutboxEventJpaEntity> findClaimableEventsWithSkipLocked(
            @Param("maxRetries") int maxRetries,
            @Param("now") Instant now,
            @Param("leaseCutoff") Instant leaseCutoff,
            Pageable pageable
    );
}
