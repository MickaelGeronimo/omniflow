package com.omniflow.infrastructure.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniflow.application.port.in.ReconcileLedgerUseCase;
import com.omniflow.application.port.out.LedgerRepositoryPort;
import com.omniflow.application.port.out.S3StoragePort;
import com.omniflow.domain.ledger.JournalEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ReconciliationBatchLauncher implements ReconcileLedgerUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationBatchLauncher.class);

    private final JobLauncher jobLauncher;
    private final Job reconciliationJob;
    private final LedgerRepositoryPort ledgerRepository;
    private final S3StoragePort s3Storage;
    private final ObjectMapper objectMapper;
    private final String s3BucketName;

    // Thread-safe buffer for chunk records during job execution
    private final List<ReconciliationBatchConfig.DiscrepancyRecord> processedRecords = new CopyOnWriteArrayList<>();

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

    @Bean
    public ItemReader<JournalEntry> ledgerItemReader() {
        return () -> {
            List<JournalEntry> entries = ledgerRepository.findAllJournalEntries();
            return new ListItemReader<>(entries).read();
        };
    }

    @Bean
    public ItemWriter<ReconciliationBatchConfig.DiscrepancyRecord> s3ReportWriter() {
        return items -> processedRecords.addAll(items.getItems());
    }

    @Override
    public ReconciliationResult runNightlyReconciliation(LocalDate date) {
        processedRecords.clear();
        String jobId = "RECON-" + date + "-" + System.currentTimeMillis();

        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("jobId", jobId)
                    .addLocalDate("date", date)
                    .toJobParameters();

            log.info("[SPRING-BATCH] Starting Nightly Financial Reconciliation Job [{}] for date [{}]", jobId, date);
            JobExecution execution = jobLauncher.run(reconciliationJob, params);

            long total = processedRecords.size();
            long discrepancies = processedRecords.stream()
                    .filter(r -> "DISCREPANCY_DETECTED".equals(r.status()))
                    .count();
            long matched = total - discrepancies;

            // Generate JSON summary for AWS S3
            Map<String, Object> summaryReport = Map.of(
                    "jobId", jobId,
                    "reconciliationDate", date.toString(),
                    "totalAudited", total,
                    "matchedCount", matched,
                    "discrepancyCount", discrepancies,
                    "status", execution.getStatus().name(),
                    "items", processedRecords
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
