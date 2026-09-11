package com.omniflow.application.port.in;

import java.time.LocalDate;

public interface ReconcileLedgerUseCase {

    record ReconciliationResult(
            String jobId,
            LocalDate reconciliationDate,
            long totalTransactionsAudited,
            long matchedCount,
            long discrepancyCount,
            String reportS3Url
    ) {}

    ReconciliationResult runNightlyReconciliation(LocalDate date);
}
