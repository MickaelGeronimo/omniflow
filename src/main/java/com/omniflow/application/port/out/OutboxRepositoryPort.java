package com.omniflow.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OutboxRepositoryPort {

    record OutboxRecord(
            String id,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload,
            String status,
            int retryCount,
            Instant nextRetryAt,
            String lockedBy,
            Instant lockedAt,
            Instant createdAt
    ) {}

    void saveEvent(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload);

    List<OutboxRecord> claimPendingEvents(String workerId, int limit);

    void markPublished(String eventId);

    void recordFailure(String eventId, String error, int maxRetries);
}
