package com.omniflow.infrastructure.adapter.out.aws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniflow.application.port.out.AuditEventStorePort;
import com.omniflow.application.port.out.LedgerRepositoryPort;
import com.omniflow.application.port.out.TransactionRepositoryPort;
import com.omniflow.domain.ledger.JournalEntry;
import com.omniflow.domain.ledger.LedgerAccount;
import com.omniflow.domain.ledger.PostingLeg;
import com.omniflow.domain.model.AccountId;
import com.omniflow.domain.model.Money;
import com.omniflow.domain.model.Transaction;
import com.omniflow.domain.model.TransactionId;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.omniflow.application.service.FinancialOrchestratorService.SETTLEMENT_TRANSIT_ACCOUNT;

@Component
public class SqsSettlementConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsSettlementConsumer.class);

    private final TransactionRepositoryPort transactionRepo;
    private final LedgerRepositoryPort ledgerRepo;
    private final AuditEventStorePort auditStore;
    private final com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataProcessedEventRepository processedEventRepo;
    private final ObjectMapper objectMapper;
    private final Counter settledCounter;
    private final Counter poisonPillCounter;
    private final Timer settlementTimer;

    public SqsSettlementConsumer(
            TransactionRepositoryPort transactionRepo,
            LedgerRepositoryPort ledgerRepo,
            AuditEventStorePort auditStore,
            com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataProcessedEventRepository processedEventRepo,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.transactionRepo = transactionRepo;
        this.ledgerRepo = ledgerRepo;
        this.auditStore = auditStore;
        this.processedEventRepo = processedEventRepo;
        this.objectMapper = objectMapper;

        this.settledCounter = Counter.builder("omniflow.transactions.settled")
                .description("Total transactions successfully settled via AWS SQS")
                .register(meterRegistry);
        this.poisonPillCounter = Counter.builder("omniflow.sqs.poison.pill")
                .description("Total messages routed to Dead Letter Queue")
                .register(meterRegistry);
        this.settlementTimer = Timer.builder("omniflow.settlement.latency")
                .description("Latency for SQS settlement execution")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @SqsListener("${omniflow.aws.sqs-queue-name}")
    @Transactional
    public void onMessage(
            @Payload String rawMessage,
            @Header(value = "correlationId", required = false) String correlationId) {
        Timer.Sample sample = Timer.start();
        MDC.put("correlationId", correlationId != null ? correlationId : "SQS-" + Instant.now().toEpochMilli());

        try {
            log.info("[AWS-SQS] Received message from settlement queue");

            // Handle SNS unwrapping if message is delivered via SNS fan-out
            Map<String, Object> data = unwrapSnsIfNeeded(rawMessage);

            String transactionId = (String) data.get("transactionId");
            String creditorAccountStr = (String) data.get("creditorAccount");
            String amountStr = (String) data.get("amount");
            String currency = (String) data.get("currency");

            if (transactionId == null) {
                log.warn("[AWS-SQS] Missing transactionId in payload. Rejecting message to DLQ");
                poisonPillCounter.increment();
                return;
            }

            String eventId = (String) data.getOrDefault("eventId", "SETTLE-EVT-" + transactionId);
            if (processedEventRepo.existsById(eventId)) {
                log.info("[AWS-SQS] Duplicate event [{}] detected for tx [{}]. Skipping idempotently.", eventId, transactionId);
                return;
            }

            // Atomic Deduplication Token: Closes the TOCTOU concurrency race window before executing balance modifications
            int inserted = processedEventRepo.insertIfNotExists(eventId, transactionId, correlationId, Instant.now());
            if (inserted == 0) {
                log.info("[AWS-SQS] Duplicate event [{}] detected via atomic token reservation for tx [{}]. Skipping idempotently.", eventId, transactionId);
                return;
            }

            // Record raw audit event in MongoDB
            auditStore.recordAuditEvent(transactionId, "SQS_SETTLEMENT_RECEIVED", "omniflow-worker", data);

            // Execute Settle Step: Debit Transit, Credit Creditor
            Transaction tx = transactionRepo.findById(TransactionId.of(transactionId))
                    .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));

            if (tx.getStatus() == com.omniflow.domain.model.TransactionStatus.SETTLED) {
                log.info("[AWS-SQS] Transaction [{}] is already SETTLED. Skipping duplicate settlement idempotently.", transactionId);
                return;
            }

            LedgerAccount transit = ledgerRepo.findAccountById(SETTLEMENT_TRANSIT_ACCOUNT)
                    .orElseThrow(() -> new IllegalStateException("Transit account missing"));
            LedgerAccount creditor = ledgerRepo.findAccountById(AccountId.parse(creditorAccountStr))
                    .orElseThrow(() -> new IllegalArgumentException("Creditor account missing: " + creditorAccountStr));

            Money amount = Money.of(amountStr, currency);

            PostingLeg leg1 = PostingLeg.debit(transit.getId(), amount, "Settlement Release tx " + transactionId);
            PostingLeg leg2 = PostingLeg.credit(creditor.getId(), amount, "Settlement Credit tx " + transactionId);

            JournalEntry settlementJournal = new JournalEntry(
                    "SETTLE-" + transactionId,
                    tx.getReferenceId(),
                    "Final settlement for tx " + transactionId,
                    List.of(leg1, leg2)
            );

            transit.applyLeg(leg1);
            creditor.applyLeg(leg2);
            tx.markSettled();

            ledgerRepo.saveAccount(transit);
            ledgerRepo.saveAccount(creditor);
            ledgerRepo.saveJournalEntry(settlementJournal);
            transactionRepo.save(tx);

            auditStore.recordAuditEvent(transactionId, "SETTLED_COMPLETED", "omniflow-worker", Map.of(
                    "status", "SETTLED",
                    "settledAt", Instant.now().toString()
            ));

            settledCounter.increment();
            sample.stop(settlementTimer);
            log.info("[AWS-SQS] Successfully settled tx [{}] for amount [{} {}]", transactionId, amountStr, currency);

        } catch (Exception e) {
            log.error("[AWS-SQS] Failed processing settlement message. Triggering SQS redrive to DLQ", e);
            poisonPillCounter.increment();
            throw new RuntimeException("SQS processing failure", e);
        } finally {
            MDC.remove("correlationId");
        }
    }

    private Map<String, Object> unwrapSnsIfNeeded(String raw) {
        try {
            Map<String, Object> map = objectMapper.readValue(raw, new TypeReference<>() {});
            if (map.containsKey("Type") && "Notification".equals(map.get("Type")) && map.containsKey("Message")) {
                String innerMessage = (String) map.get("Message");
                return objectMapper.readValue(innerMessage, new TypeReference<>() {});
            }
            return map;
        } catch (Exception e) {
            throw new RuntimeException("Payload unwrap error", e);
        }
    }
}
