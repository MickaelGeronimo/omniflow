package com.omniflow.infrastructure.adapter.out.persistence.postgres.repository;

import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.JournalEntryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataJournalEntryRepository extends JpaRepository<JournalEntryJpaEntity, String> {
}
