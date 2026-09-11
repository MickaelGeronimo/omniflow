package com.omniflow.infrastructure.adapter.out.aws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniflow.application.port.out.OutboxRepositoryPort;
import com.omniflow.application.port.out.SnsPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OutboxRelayScheduledWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduledWorker.class);

    private final OutboxRepositoryPort outboxRepository;
    private final SnsPublisherPort snsPublisher;
    private final ObjectMapper objectMapper;
    private final String workerId = "outbox-worker-" + UUID.randomUUID().toString().substring(0, 8);
    private final String snsTopicName;

    public OutboxRelayScheduledWorker(
            OutboxRepositoryPort outboxRepository,
            SnsPublisherPort snsPublisher,
            ObjectMapper objectMapper,
            @Value("${omniflow.aws.sns-topic-name:omniflow-transactions-topic}") String snsTopicName) {
        this.outboxRepository = outboxRepository;
        this.snsPublisher = snsPublisher;
        this.objectMapper = objectMapper;
        this.snsTopicName = snsTopicName;
    }

    @Scheduled(fixedDelayString = "${omniflow.outbox.poll-interval-ms:2000}")
    public void relayOutboxEventsToSns() {
        List<OutboxRepositoryPort.OutboxRecord> events = outboxRepository.claimPendingEvents(workerId, 10);
        if (events.isEmpty()) {
            return;
        }

        log.debug("[OUTBOX-RELAY:{}] Claimed {} events for SNS publication", workerId, events.size());

        for (OutboxRepositoryPort.OutboxRecord event : events) {
            try {
                Map<String, Object> payload = objectMapper.readValue(event.payload(), new TypeReference<>() {});
                String correlationId = (String) payload.getOrDefault("referenceId", event.aggregateId());

                snsPublisher.publish(snsTopicName, event.eventType(), correlationId, payload);
                outboxRepository.markPublished(event.id());
                log.info("[OUTBOX-RELAY:{}] Published event [{}:{}] to SNS", workerId, event.eventType(), event.id());
            } catch (Exception e) {
                log.error("[OUTBOX-RELAY:{}] Error relaying event [{}]", workerId, event.id(), e);
                outboxRepository.recordFailure(event.id(), e.getMessage(), 3);
            }
        }
    }
}
