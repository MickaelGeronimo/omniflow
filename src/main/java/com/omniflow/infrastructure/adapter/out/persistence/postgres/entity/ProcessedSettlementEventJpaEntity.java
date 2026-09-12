package com.omniflow.infrastructure.adapter.out.persistence.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "processed_settlement_events")
public class ProcessedSettlementEventJpaEntity {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedSettlementEventJpaEntity() {}

    public ProcessedSettlementEventJpaEntity(String eventId, String transactionId, String correlationId, Instant processedAt) {
        this.eventId = eventId;
        this.transactionId = transactionId;
        this.correlationId = correlationId;
        this.processedAt = processedAt;
    }

    public String getEventId() { return eventId; }
    public String getTransactionId() { return transactionId; }
    public String getCorrelationId() { return correlationId; }
    public Instant getProcessedAt() { return processedAt; }
}
