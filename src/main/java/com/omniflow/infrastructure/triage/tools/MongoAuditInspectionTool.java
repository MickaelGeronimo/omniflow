package com.omniflow.infrastructure.triage.tools;

import com.omniflow.application.port.out.AuditEventStorePort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MongoAuditInspectionTool {

    private final AuditEventStorePort auditStore;

    public MongoAuditInspectionTool(AuditEventStorePort auditStore) {
        this.auditStore = auditStore;
    }

    @Tool(description = "Query the MongoDB audit trail for a transaction to retrieve all chronological events and states")
    public Map<String, Object> queryTransactionAuditTrail(String transactionId) {
        List<AuditEventStorePort.AuditRecord> records = auditStore.findByTransactionId(transactionId);
        return Map.of(
                "transactionId", transactionId,
                "eventCount", records.size(),
                "events", records.stream().map(r -> Map.of(
                        "eventType", r.eventType(),
                        "origin", r.originService(),
                        "timestamp", r.timestamp().toString(),
                        "payload", r.payload()
                )).toList()
        );
    }
}
