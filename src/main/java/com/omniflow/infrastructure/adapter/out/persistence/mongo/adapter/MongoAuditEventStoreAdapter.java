package com.omniflow.infrastructure.adapter.out.persistence.mongo.adapter;

import com.omniflow.application.port.out.AuditEventStorePort;
import com.omniflow.infrastructure.adapter.out.persistence.mongo.document.AuditEventDocument;
import com.omniflow.infrastructure.adapter.out.persistence.mongo.document.AgentTriageLogDocument;
import com.omniflow.infrastructure.adapter.out.persistence.mongo.repository.SpringDataMongoAgentLogRepository;
import com.omniflow.infrastructure.adapter.out.persistence.mongo.repository.SpringDataMongoAuditRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class MongoAuditEventStoreAdapter implements AuditEventStorePort {

    private final SpringDataMongoAuditRepository auditRepo;
    private final SpringDataMongoAgentLogRepository agentLogRepo;

    public MongoAuditEventStoreAdapter(
            SpringDataMongoAuditRepository auditRepo,
            SpringDataMongoAgentLogRepository agentLogRepo) {
        this.auditRepo = auditRepo;
        this.agentLogRepo = agentLogRepo;
    }

    @Override
    public void recordAuditEvent(String transactionId, String eventType, String originService, Map<String, Object> payload) {
        AuditEventDocument doc = new AuditEventDocument(
                UUID.randomUUID().toString(),
                transactionId,
                eventType,
                originService,
                payload,
                Instant.now()
        );
        auditRepo.save(doc);
    }

    @Override
    public List<AuditRecord> findByTransactionId(String transactionId) {
        return auditRepo.findByTransactionIdOrderByTimestampAsc(transactionId).stream()
                .map(doc -> new AuditRecord(
                        doc.getId(),
                        doc.getTransactionId(),
                        doc.getEventType(),
                        doc.getOriginService(),
                        doc.getPayload(),
                        doc.getTimestamp()
                )).toList();
    }

    @Override
    public void recordAgentDecision(String incidentId, String transactionId, String reasoning, String recommendation, Map<String, Object> evidence) {
        AgentTriageLogDocument logDoc = new AgentTriageLogDocument(
                UUID.randomUUID().toString(),
                incidentId,
                transactionId,
                reasoning,
                recommendation,
                evidence,
                Instant.now()
        );
        agentLogRepo.save(logDoc);
    }
}
