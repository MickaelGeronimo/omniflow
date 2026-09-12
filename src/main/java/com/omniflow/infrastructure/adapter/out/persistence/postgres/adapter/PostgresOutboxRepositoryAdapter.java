package com.omniflow.infrastructure.adapter.out.persistence.postgres.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniflow.application.port.out.OutboxRepositoryPort;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.OutboxEventJpaEntity;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataOutboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Repository
public class PostgresOutboxRepositoryAdapter implements OutboxRepositoryPort {

    private final SpringDataOutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public PostgresOutboxRepositoryAdapter(SpringDataOutboxRepository outboxRepo, ObjectMapper objectMapper) {
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveEvent(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            String correlationId = (String) payload.getOrDefault("correlationId", payload.getOrDefault("referenceId", aggregateId));

            OutboxEventJpaEntity entity = new OutboxEventJpaEntity(
                    UUID.randomUUID().toString(),
                    aggregateType,
                    aggregateId,
                    eventType,
                    correlationId,
                    json,
                    "PENDING",
                    0,
                    null,
                    null,
                    null,
                    Instant.now(),
                    null
            );
            outboxRepo.save(entity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize outbox event payload", e);
        }
    }

    @Override
    @Transactional
    public List<OutboxRecord> claimPendingEvents(String workerId, int limit) {
        Instant now = Instant.now();
        Instant leaseCutoff = now.minus(5, ChronoUnit.MINUTES); // 5-minute lease recovery for crashed pods
        List<OutboxEventJpaEntity> claimable = outboxRepo.findClaimableEventsWithSkipLocked(
                3, now, leaseCutoff, PageRequest.of(0, limit)
        );

        for (OutboxEventJpaEntity event : claimable) {
            event.setStatus("PROCESSING");
            event.setLockedBy(workerId);
            event.setLockedAt(now);
        }
        outboxRepo.saveAll(claimable);

        return claimable.stream().map(e -> new OutboxRecord(
                e.getId(),
                e.getAggregateType(),
                e.getAggregateId(),
                e.getEventType(),
                e.getPayload(),
                e.getStatus(),
                e.getRetryCount(),
                e.getNextRetryAt(),
                e.getLockedBy(),
                e.getLockedAt(),
                e.getCreatedAt()
        )).toList();
    }

    @Override
    @Transactional
    public void markPublished(String eventId) {
        outboxRepo.findById(eventId).ifPresent(event -> {
            event.setStatus("PUBLISHED");
            event.setPublishedAt(Instant.now());
            event.setLockedBy(null);
            event.setLockedAt(null);
            outboxRepo.save(event);
        });
    }

    @Override
    @Transactional
    public void recordFailure(String eventId, String error, int maxRetries) {
        outboxRepo.findById(eventId).ifPresent(event -> {
            int retries = event.getRetryCount() + 1;
            event.setRetryCount(retries);
            event.setLockedBy(null);
            event.setLockedAt(null);

            if (retries >= maxRetries) {
                event.setStatus("DEAD_LETTER");
            } else {
                event.setStatus("FAILED");
                // Exponential backoff with full random jitter: base * 2^retries + jitter (0-1000ms)
                long baseDelaySec = 2L;
                long maxDelaySec = 300L;
                long expDelaySec = Math.min(maxDelaySec, baseDelaySec * (1L << Math.min(retries, 8)));
                long jitterMillis = ThreadLocalRandom.current().nextLong(0, 1000);
                event.setNextRetryAt(Instant.now().plusSeconds(expDelaySec).plusMillis(jitterMillis));
            }
            outboxRepo.save(event);
        });
    }
}
