package com.omniflow.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface AuditEventStorePort {

    record AuditRecord(
            String id,
            String transactionId,
            String eventType,
            String originService,
            Map<String, Object> payload,
            Instant timestamp
    ) {}

    void recordAuditEvent(String transactionId, String eventType, String originService, Map<String, Object> payload);

    List<AuditRecord> findByTransactionId(String transactionId);

    void recordAgentDecision(String incidentId, String transactionId, String reasoning, String recommendation, Map<String, Object> evidence);
}
