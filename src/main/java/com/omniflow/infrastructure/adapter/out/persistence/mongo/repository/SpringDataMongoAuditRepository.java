package com.omniflow.infrastructure.adapter.out.persistence.mongo.repository;

import com.omniflow.infrastructure.adapter.out.persistence.mongo.document.AuditEventDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SpringDataMongoAuditRepository extends MongoRepository<AuditEventDocument, String> {

    List<AuditEventDocument> findByTransactionIdOrderByTimestampAsc(String transactionId);
}
