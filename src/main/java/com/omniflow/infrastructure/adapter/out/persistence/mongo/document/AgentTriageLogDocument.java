package com.omniflow.infrastructure.adapter.out.persistence.mongo.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "ai_agent_triage_logs")
public class AgentTriageLogDocument {

    @Id
    private String id;

    @Indexed
    private String incidentId;

    @Indexed
    private String transactionId;

    private String reasoning;
    private String recommendation;
    private Map<String, Object> evidence;
    private Instant createdAt;

    public AgentTriageLogDocument() {}

    public AgentTriageLogDocument(String id, String incidentId, String transactionId,
                                 String reasoning, String recommendation, Map<String, Object> evidence, Instant createdAt) {
        this.id = id;
        this.incidentId = incidentId;
        this.transactionId = transactionId;
        this.reasoning = reasoning;
        this.recommendation = recommendation;
        this.evidence = evidence;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getIncidentId() { return incidentId; }
    public String getTransactionId() { return transactionId; }
    public String getReasoning() { return reasoning; }
    public String getRecommendation() { return recommendation; }
    public Map<String, Object> getEvidence() { return evidence; }
    public Instant getCreatedAt() { return createdAt; }
}
