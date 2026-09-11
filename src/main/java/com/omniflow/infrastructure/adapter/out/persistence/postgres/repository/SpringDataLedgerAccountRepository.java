package com.omniflow.infrastructure.adapter.out.persistence.postgres.repository;

import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.LedgerAccountJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataLedgerAccountRepository extends JpaRepository<LedgerAccountJpaEntity, String> {
}
