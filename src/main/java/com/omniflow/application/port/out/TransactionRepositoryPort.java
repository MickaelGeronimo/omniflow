package com.omniflow.application.port.out;

import com.omniflow.domain.model.Transaction;
import com.omniflow.domain.model.TransactionId;

import java.util.Optional;

public interface TransactionRepositoryPort {

    Transaction save(Transaction transaction);

    Optional<Transaction> findById(TransactionId id);

    Optional<Transaction> findByReferenceId(String referenceId);
}
