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
import java.time.Instant;
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
     * Keyset (Cursor) ItemReader with Temporal Cutoff:
     * Reads from PostgreSQL in bounded batches of 100 using 'WHERE entry_id > :lastEntryId AND timestamp <= :cutoff'.
     * Avoids O(N) offset degradation and phantom skipping while guaranteeing reproducible snapshots.
     */
    @Bean
    @StepScope
    public ItemReader<JournalEntry> ledgerItemReader(
            @Value("#{jobParameters['cutoffTimestamp']}") String cutoffParam) {
        final Instant cutoff = (cutoffParam != null && !cutoffParam.isBlank())
                ? Instant.parse(cutoffParam)
                : Instant.now();

        return new ItemReader<>() {
            private String lastEntryId = null;
            private Iterator<JournalEntry> currentChunk = Collections.emptyIterator();
            private boolean exhausted = false;

            @Override
            public synchronized JournalEntry read() {
                if (exhausted) {
                    return null;
                }
                if (!currentChunk.hasNext()) {
                    List<JournalEntry> nextBatch = ledgerRepository.findJournalEntriesKeyset(lastEntryId, cutoff, 100);
                    if (nextBatch.isEmpty()) {
                        exhausted = true;
                        return null; // signals EOF to Spring Batch
                    }
                    lastEntryId = nextBatch.get(nextBatch.size() - 1).getEntryId();
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
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evidenceList = (List<Map<String, Object>>) context.get("discrepancies");
            if (evidenceList == null) {
                evidenceList = new ArrayList<>();
            }

            for (ReconciliationBatchConfig.DiscrepancyRecord record : items) {
                if ("DISCREPANCY_DETECTED".equals(record.status())) {
                    discrepancies++;
                    if (evidenceList.size() < 100) { // Bounded forensic sample
                        evidenceList.add(Map.of(
                                "entryId", record.entryId(),
                                "referenceId", record.referenceId(),
                                "discrepancyAmount", record.discrepancyAmount().toPlainString(),
                                "status", record.status()
                        ));
                    }
                }
            }

            context.putLong("totalAudited", total);
            context.putLong("discrepancyCount", discrepancies);
            context.putLong("matchedCount", total - discrepancies);
            context.put("discrepancies", evidenceList);
        };
    }

    @Override
    public ReconciliationResult runNightlyReconciliation(LocalDate date) {
        String jobId = "RECON-" + date + "-" + System.currentTimeMillis();
        Instant cutoff = Instant.now();

        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("jobId", jobId)
                    .addLocalDate("date", date)
                    .addString("cutoffTimestamp", cutoff.toString())
                    .toJobParameters();

            log.info("[SPRING-BATCH] Starting Nightly Financial Reconciliation Job [{}] for date [{}] with cutoff [{}]",
                    jobId, date, cutoff);
            JobExecution execution = jobLauncher.run(reconciliationJob, params);

            long total = execution.getExecutionContext().getLong("totalAudited", 0L);
            long discrepancies = execution.getExecutionContext().getLong("discrepancyCount", 0L);
            long matched = execution.getExecutionContext().getLong("matchedCount", 0L);
            Object evidence = execution.getExecutionContext().get("discrepancies");

            // Generate detailed JSON summary report for AWS S3 with evidence metadata
            Map<String, Object> summaryReport = new LinkedHashMap<>();
            summaryReport.put("jobId", jobId);
            summaryReport.put("reconciliationDate", date.toString());
            summaryReport.put("cutoffTimestamp", cutoff.toString());
            summaryReport.put("totalAudited", total);
            summaryReport.put("matchedCount", matched);
            summaryReport.put("discrepancyCount", discrepancies);
            summaryReport.put("status", execution.getStatus().name());
            summaryReport.put("discrepancies", evidence != null ? evidence : Collections.emptyList());

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
