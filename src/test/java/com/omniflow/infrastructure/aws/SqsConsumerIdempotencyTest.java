package com.omniflow.infrastructure.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniflow.application.port.out.AuditEventStorePort;
import com.omniflow.application.port.out.LedgerRepositoryPort;
import com.omniflow.application.port.out.TransactionRepositoryPort;
import com.omniflow.domain.ledger.AccountType;
import com.omniflow.domain.ledger.LedgerAccount;
import com.omniflow.domain.model.*;
import com.omniflow.infrastructure.adapter.out.aws.SqsSettlementConsumer;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataProcessedEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.omniflow.application.service.FinancialOrchestratorService.SETTLEMENT_TRANSIT_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SqsConsumerIdempotencyTest {

    private TransactionRepositoryPort transactionRepo;
    private LedgerRepositoryPort ledgerRepo;
    private AuditEventStorePort auditStore;
    private SpringDataProcessedEventRepository processedEventRepo;
    private SqsSettlementConsumer consumer;

    @BeforeEach
    void setUp() {
        transactionRepo = Mockito.mock(TransactionRepositoryPort.class);
        ledgerRepo = Mockito.mock(LedgerRepositoryPort.class);
        auditStore = Mockito.mock(AuditEventStorePort.class);
        processedEventRepo = Mockito.mock(SpringDataProcessedEventRepository.class);

        consumer = new SqsSettlementConsumer(
                transactionRepo,
                ledgerRepo,
                auditStore,
                processedEventRepo,
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("Should successfully settle transaction on first message delivery and record deduplication token")
    void shouldSettleOnFirstDelivery() {
        String txId = "tx-1001";
        AccountId debtor = AccountId.of("OMNI", "0001", "D1");
        AccountId creditor = AccountId.of("OMNI", "0001", "C1");

        Transaction tx = new Transaction(
                TransactionId.of(txId), "REF-1001", debtor, creditor,
                Money.usd("250.00"), TransactionStatus.PENDING, null, Instant.now(), Instant.now(), 0L
        );
        tx.markFundsReserved();

        LedgerAccount transit = new LedgerAccount(
                SETTLEMENT_TRANSIT_ACCOUNT, "Transit", AccountType.LIABILITY, "USD", Money.usd("1000.00"), true, 0L
        );
        LedgerAccount creditorAcc = new LedgerAccount(
                creditor, "Creditor", AccountType.LIABILITY, "USD", Money.usd("0.00"), false, 0L
        );

        when(processedEventRepo.existsById("EVT-1")).thenReturn(false);
        when(processedEventRepo.insertIfNotExists(eq("EVT-1"), eq(txId), eq("CORR-1001"), any())).thenReturn(1);
        when(transactionRepo.findById(TransactionId.of(txId))).thenReturn(Optional.of(tx));
        when(ledgerRepo.findAccountById(SETTLEMENT_TRANSIT_ACCOUNT)).thenReturn(Optional.of(transit));
        when(ledgerRepo.findAccountById(creditor)).thenReturn(Optional.of(creditorAcc));

        String rawPayload = """
                {
                    "eventId": "EVT-1",
                    "transactionId": "tx-1001",
                    "creditorAccount": "%s",
                    "amount": "250.00",
                    "currency": "USD"
                }
                """.formatted(creditor.toString());

        consumer.onMessage(rawPayload, "CORR-1001");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SETTLED);
        verify(transactionRepo).save(tx);
        verify(processedEventRepo).insertIfNotExists(eq("EVT-1"), eq(txId), eq("CORR-1001"), any());
        verify(ledgerRepo, times(2)).saveAccount(any());
        verify(ledgerRepo).saveJournalEntry(any());
    }

    @Test
    @DisplayName("Should skip duplicate delivery idempotently when eventId was already processed")
    void shouldSkipDuplicateDeliveryIdempotently() {
        when(processedEventRepo.existsById("EVT-1")).thenReturn(true);

        String rawPayload = """
                {
                    "eventId": "EVT-1",
                    "transactionId": "tx-1001",
                    "creditorAccount": "OMNI:0001:C1",
                    "amount": "250.00",
                    "currency": "USD"
                }
                """;

        consumer.onMessage(rawPayload, "CORR-1001");

        // Zero interactions with database ledger or transaction save
        verify(transactionRepo, never()).findById(any());
        verify(transactionRepo, never()).save(any());
        verify(ledgerRepo, never()).saveAccount(any());
    }

    @Test
    @DisplayName("Should skip delivery when atomic token reservation detects concurrent race conflict (TOCTOU closed)")
    void shouldSkipDeliveryWhenAtomicReservationDetectsConflict() {
        // Simulates concurrent arrival: existsById returned false initially, but insertIfNotExists got 0
        when(processedEventRepo.existsById("EVT-RACE")).thenReturn(false);
        when(processedEventRepo.insertIfNotExists(eq("EVT-RACE"), any(), any(), any())).thenReturn(0);

        String rawPayload = """
                {
                    "eventId": "EVT-RACE",
                    "transactionId": "tx-1002",
                    "creditorAccount": "OMNI:0001:C1",
                    "amount": "150.00",
                    "currency": "USD"
                }
                """;

        consumer.onMessage(rawPayload, "CORR-1002");

        // Atomic token returned 0 -> consumer must not touch transaction or ledger
        verify(transactionRepo, never()).findById(any());
        verify(transactionRepo, never()).save(any());
        verify(ledgerRepo, never()).saveAccount(any());
    }

    @Test
    @DisplayName("Should safely handle concurrent duplicate message deliveries across multiple threads without double settlement")
    void shouldSafelyHandleConcurrentDuplicateDeliveries() throws InterruptedException {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        String txId = "tx-race-99";
        AccountId creditor = AccountId.of("OMNI", "0001", "C99");

        Transaction tx = new Transaction(
                TransactionId.of(txId), "REF-99", AccountId.of("OMNI", "0001", "D99"), creditor,
                Money.usd("100.00"), TransactionStatus.PENDING, null, Instant.now(), Instant.now(), 0L
        );
        tx.markFundsReserved();

        LedgerAccount transit = new LedgerAccount(
                SETTLEMENT_TRANSIT_ACCOUNT, "Transit", AccountType.LIABILITY, "USD", Money.usd("1000.00"), true, 0L
        );
        LedgerAccount creditorAcc = new LedgerAccount(
                creditor, "Creditor", AccountType.LIABILITY, "USD", Money.usd("0.00"), false, 0L
        );

        when(processedEventRepo.existsById("EVT-CONCURRENT")).thenReturn(false);
        // Only the first thread that executes insertIfNotExists wins (returns 1), all other 9 threads get 0
        AtomicInteger tokenCounter = new AtomicInteger(0);
        when(processedEventRepo.insertIfNotExists(eq("EVT-CONCURRENT"), any(), any(), any()))
                .thenAnswer(invocation -> tokenCounter.incrementAndGet() == 1 ? 1 : 0);

        when(transactionRepo.findById(TransactionId.of(txId))).thenReturn(Optional.of(tx));
        when(ledgerRepo.findAccountById(SETTLEMENT_TRANSIT_ACCOUNT)).thenReturn(Optional.of(transit));
        when(ledgerRepo.findAccountById(creditor)).thenReturn(Optional.of(creditorAcc));

        String rawPayload = """
                {
                    "eventId": "EVT-CONCURRENT",
                    "transactionId": "%s",
                    "creditorAccount": "%s",
                    "amount": "100.00",
                    "currency": "USD"
                }
                """.formatted(txId, creditor.toString());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    consumer.onMessage(rawPayload, "CORR-CONCURRENT");
                } catch (Exception e) {
                    // unexpected
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // Fire all 10 threads concurrently!
        doneLatch.await();
        executor.shutdown();

        // Exactly 1 thread settled the transaction, 9 threads skipped
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SETTLED);
        verify(transactionRepo, times(1)).save(tx);
        verify(ledgerRepo, times(2)).saveAccount(any()); // 1 debit leg, 1 credit leg
        verify(ledgerRepo, times(1)).saveJournalEntry(any());
    }
}