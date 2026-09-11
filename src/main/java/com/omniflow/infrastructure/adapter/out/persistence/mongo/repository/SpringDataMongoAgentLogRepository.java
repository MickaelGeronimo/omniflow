package com.omniflow.infrastructure.adapter.out.persistence.mongo.repository;

import com.omniflow.infrastructure.adapter.out.persistence.mongo.document.AgentTriageLogDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SpringDataMongoAgentLogRepository extends MongoRepository<AgentTriageLogDocument, String> {

    Optional<AgentTriageLogDocument> findByIncidentId(String incidentId);
}
