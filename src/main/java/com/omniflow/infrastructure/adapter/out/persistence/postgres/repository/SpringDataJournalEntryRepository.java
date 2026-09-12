package com.omniflow.infrastructure.adapter.out.persistence.postgres.repository;

import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.JournalEntryJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SpringDataJournalEntryRepository extends JpaRepository<JournalEntryJpaEntity, String> {

    @Query("""
        SELECT j FROM JournalEntryJpaEntity j
        WHERE (:lastEntryId IS NULL OR j.entryId > :lastEntryId)
          AND j.timestamp <= :cutoff
        ORDER BY j.entryId ASC
    """)
    List<JournalEntryJpaEntity> findByKeyset(
            @Param("lastEntryId") String lastEntryId,
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );
}
