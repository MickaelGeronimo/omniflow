package com.omniflow.infrastructure.adapter.out.persistence.postgres.repository;

import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.TransactionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataTransactionRepository extends JpaRepository<TransactionJpaEntity, String> {

    Optional<TransactionJpaEntity> findByReferenceId(String referenceId);
}
