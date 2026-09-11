package com.omniflow.infrastructure.adapter.out.persistence.mongo.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "audit_events")
public class AuditEventDocument {

    @Id
    private String id;

    @Indexed
    private String transactionId;

    private String eventType;
    private String originService;
    private Map<String, Object> payload;
    private Instant timestamp;

    public AuditEventDocument() {}

    public AuditEventDocument(String id, String transactionId, String eventType, String originService,
                              Map<String, Object> payload, Instant timestamp) {
        this.id = id;
        this.transactionId = transactionId;
        this.eventType = eventType;
        this.originService = originService;
        this.payload = payload;
        this.timestamp = timestamp;
    }

    public String getId() { return id; }
    public String getTransactionId() { return transactionId; }
    public String getEventType() { return eventType; }
    public String getOriginService() { return originService; }
    public Map<String, Object> getPayload() { return payload; }
    public Instant getTimestamp() { return timestamp; }
}
