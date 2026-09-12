package com.omniflow.infrastructure.adapter.out.persistence.postgres.repository;

import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.ProcessedSettlementEventJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface SpringDataProcessedEventRepository extends JpaRepository<ProcessedSettlementEventJpaEntity, String> {

    @Modifying
    @Query(value = """
            INSERT INTO processed_settlement_events (event_id, transaction_id, correlation_id, processed_at)
            VALUES (:eventId, :transactionId, :correlationId, :processedAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfNotExists(
            @Param("eventId") String eventId,
            @Param("transactionId") String transactionId,
            @Param("correlationId") String correlationId,
            @Param("processedAt") Instant processedAt
    );
}
