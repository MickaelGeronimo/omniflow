package com.omniflow.infrastructure.adapter.out.persistence.postgres.repository;

import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.IdempotencyKeyJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataIdempotencyRepository extends JpaRepository<IdempotencyKeyJpaEntity, String> {
}
