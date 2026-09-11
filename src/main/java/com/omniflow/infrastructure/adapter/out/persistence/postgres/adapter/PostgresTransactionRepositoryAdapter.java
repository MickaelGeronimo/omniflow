package com.omniflow.infrastructure.adapter.out.persistence.postgres.adapter;

import com.omniflow.application.port.out.TransactionRepositoryPort;
import com.omniflow.domain.model.*;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.TransactionJpaEntity;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataTransactionRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class PostgresTransactionRepositoryAdapter implements TransactionRepositoryPort {

    private final SpringDataTransactionRepository transactionRepo;

    public PostgresTransactionRepositoryAdapter(SpringDataTransactionRepository transactionRepo) {
        this.transactionRepo = transactionRepo;
    }

    @Override
    @Transactional
    public Transaction save(Transaction tx) {
        TransactionJpaEntity entity = new TransactionJpaEntity(
                tx.getId().value(),
                tx.getReferenceId(),
                tx.getDebtorAccount().toString(),
                tx.getCreditorAccount().toString(),
                tx.getAmount().amount(),
                tx.getAmount().currencyCode(),
                tx.getStatus(),
                tx.getFailureReason(),
                tx.getCreatedAt(),
                tx.getUpdatedAt(),
                tx.getVersion()
        );
        transactionRepo.save(entity);
        return tx;
    }

    @Override
    public Optional<Transaction> findById(TransactionId id) {
        return transactionRepo.findById(id.value()).map(this::toDomain);
    }

    @Override
    public Optional<Transaction> findByReferenceId(String referenceId) {
        return transactionRepo.findByReferenceId(referenceId).map(this::toDomain);
    }

    private Transaction toDomain(TransactionJpaEntity entity) {
        return new Transaction(
                TransactionId.of(entity.getTransactionId()),
                entity.getReferenceId(),
                AccountId.parse(entity.getDebtorAccount()),
                AccountId.parse(entity.getCreditorAccount()),
                Money.of(entity.getAmount(), entity.getCurrency()),
                entity.getStatus(),
                entity.getFailureReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion() != null ? entity.getVersion() : 0L
        );
    }
}
