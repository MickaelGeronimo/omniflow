package com.omniflow.infrastructure.batch;

import com.omniflow.domain.ledger.JournalEntry;
import com.omniflow.domain.ledger.PostingLeg;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;

@Configuration
public class ReconciliationBatchConfig {

    public record AuditItem(String entryId, String referenceId, BigDecimal totalDebits, BigDecimal totalCredits, boolean balanced) {}

    public record DiscrepancyRecord(String entryId, String referenceId, BigDecimal discrepancyAmount, String status) {}

    @Bean
    public Job reconciliationJob(JobRepository jobRepository, Step reconciliationStep) {
        return new JobBuilder("reconciliationJob", jobRepository)
                .start(reconciliationStep)
                .build();
    }

    @Bean
    public Step reconciliationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemReader<JournalEntry> ledgerItemReader,
            ItemProcessor<JournalEntry, DiscrepancyRecord> discrepancyProcessor,
            ItemWriter<DiscrepancyRecord> s3ReportWriter,
            StepExecutionListener promotionListener) {
        return new StepBuilder("reconciliationStep", jobRepository)
                .<JournalEntry, DiscrepancyRecord>chunk(100, transactionManager)
                .reader(ledgerItemReader)
                .processor(discrepancyProcessor)
                .writer(s3ReportWriter)
                .listener(promotionListener)
                .build();
    }

    @Bean
    public StepExecutionListener promotionListener() {
        ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
        listener.setKeys(new String[]{"totalAudited", "matchedCount", "discrepancyCount", "discrepancies"});
        return listener;
    }

    @Bean
    public ItemProcessor<JournalEntry, DiscrepancyRecord> discrepancyProcessor() {
        return journal -> {
            BigDecimal debits = BigDecimal.ZERO;
            BigDecimal credits = BigDecimal.ZERO;

            for (PostingLeg leg : journal.getLegs()) {
                if (leg.type() == com.omniflow.domain.ledger.PostingType.DEBIT) {
                    debits = debits.add(leg.amount().amount());
                } else {
                    credits = credits.add(leg.amount().amount());
                }
            }

            BigDecimal diff = debits.subtract(credits).abs();
            if (diff.compareTo(BigDecimal.ZERO) == 0) {
                return new DiscrepancyRecord(journal.getEntryId(), journal.getReferenceId(), BigDecimal.ZERO, "MATCHED");
            } else {
                return new DiscrepancyRecord(journal.getEntryId(), journal.getReferenceId(), diff, "DISCREPANCY_DETECTED");
            }
        };
    }
}
