package com.omniflow.infrastructure.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniflow.application.port.in.ReconcileLedgerUseCase;
import com.omniflow.application.port.out.LedgerRepositoryPort;
import com.omniflow.application.port.out.S3StoragePort;
import com.omniflow.domain.ledger.JournalEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@Service
public class ReconciliationBatchLauncher implements ReconcileLedgerUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationBatchLauncher.class);

    private final JobLauncher jobLauncher;
    private final Job reconciliationJob;
    private final LedgerRepositoryPort ledgerRepository;
    private final S3StoragePort s3Storage;
    private final ObjectMapper objectMapper;
    private final String s3BucketName;

    public ReconciliationBatchLauncher(
            JobLauncher jobLauncher,
            Job reconciliationJob,
            LedgerRepositoryPort ledgerRepository,
            S3StoragePort s3Storage,
            ObjectMapper objectMapper,
            @Value("${omniflow.aws.s3-bucket-name:omniflow-reconciliation-reports}") String s3BucketName) {
        this.jobLauncher = jobLauncher;
        this.reconciliationJob = reconciliationJob;
        this.ledgerRepository = ledgerRepository;
        this.s3Storage = s3Storage;
        this.objectMapper = objectMapper;
        this.s3BucketName = s3BucketName;
    }

    /**
     * Chunk-based Paged ItemReader:
     * Reads from PostgreSQL in bounded pages of 100 records.
     * Guarantees O(1) constant memory consumption regardless of dataset size.
     */
    @Bean
    @StepScope
    public ItemReader<JournalEntry> ledgerItemReader() {
        return new ItemReader<>() {
            private int page = 0;
            private Iterator<JournalEntry> currentChunk = Collections.emptyIterator();

            @Override
            public synchronized JournalEntry read() {
                if (!currentChunk.hasNext()) {
                    List<JournalEntry> nextBatch = ledgerRepository.findJournalEntriesPaged(page++, 100);
                    if (nextBatch.isEmpty()) {
                        return null; // signals EOF to Spring Batch
                    }
                    currentChunk = nextBatch.iterator();
                }
                return currentChunk.next();
            }
        };
    }

    /**
     * Stateless StepScope Writer:
     * Maintains chunk metrics directly inside Spring Batch ExecutionContext,
     * completely eliminating mutable state in singleton Spring services.
     */
    @Bean
    @StepScope
    public ItemWriter<ReconciliationBatchConfig.DiscrepancyRecord> s3ReportWriter(
            @Value("#{stepExecution}") StepExecution stepExecution) {
        return items -> {
            ExecutionContext context = stepExecution.getExecutionContext();
            long total = context.getLong("totalAudited", 0L) + items.size();
            long discrepancies = context.getLong("discrepancyCount", 0L);

            for (ReconciliationBatchConfig.DiscrepancyRecord record : items) {
                if ("DISCREPANCY_DETECTED".equals(record.status())) {
                    discrepancies++;
                }
            }

            context.putLong("totalAudited", total);
            context.putLong("discrepancyCount", discrepancies);
            context.putLong("matchedCount", total - discrepancies);
        };
    }

    @Override
    public ReconciliationResult runNightlyReconciliation(LocalDate date) {
        String jobId = "RECON-" + date + "-" + System.currentTimeMillis();

        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("jobId", jobId)
                    .addLocalDate("date", date)
                    .toJobParameters();

            log.info("[SPRING-BATCH] Starting Nightly Financial Reconciliation Job [{}] for date [{}]", jobId, date);
            JobExecution execution = jobLauncher.run(reconciliationJob, params);

            long total = execution.getExecutionContext().getLong("totalAudited", 0L);
            long discrepancies = execution.getExecutionContext().getLong("discrepancyCount", 0L);
            long matched = execution.getExecutionContext().getLong("matchedCount", 0L);

            // Generate JSON summary report for AWS S3
            Map<String, Object> summaryReport = Map.of(
                    "jobId", jobId,
                    "reconciliationDate", date.toString(),
                    "totalAudited", total,
                    "matchedCount", matched,
                    "discrepancyCount", discrepancies,
                    "status", execution.getStatus().name()
            );

            byte[] reportBytes = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(summaryReport)
                    .getBytes(StandardCharsets.UTF_8);

            String key = String.format("reconciliation/%s/%s-report.json", date, jobId);
            String reportUrl = s3Storage.uploadReport(s3BucketName, key, reportBytes, "application/json");

            log.info("[SPRING-BATCH] Completed Reconciliation Job [{}]. Total: {}, Matched: {}, Discrepancies: {}. Report: {}",
                    jobId, total, matched, discrepancies, reportUrl);

            return new ReconciliationResult(jobId, date, total, matched, discrepancies, reportUrl);

        } catch (Exception e) {
            log.error("[SPRING-BATCH] Reconciliation job [{}] execution failed", jobId, e);
            throw new RuntimeException("Batch reconciliation failed", e);
        }
    }
}
