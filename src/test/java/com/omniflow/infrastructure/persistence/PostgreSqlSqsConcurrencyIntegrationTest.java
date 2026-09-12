package com.omniflow.infrastructure.persistence;

import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.LedgerAccountJpaEntity;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataLedgerAccountRepository;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataProcessedEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlSqsConcurrencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("omniflow_test")
            .withUsername("omniflow")
            .withPassword("secret");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    private SpringDataProcessedEventRepository processedEventRepo;

    @Autowired
    private SpringDataLedgerAccountRepository accountRepo;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Should prove real PostgreSQL 16 ON CONFLICT DO NOTHING resolves 10 concurrent threads with exactly 1 winner and 9 conflicts")
    void shouldProveRealPostgreSqlResolvesConcurrentRace() throws InterruptedException {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        String eventId = "EVT-POSTGRES-RACE-100";
        String txId = "tx-pg-100";

        List<Integer> results = Collections.synchronizedList(new ArrayList<>());
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    Integer res = txTemplate.execute(status ->
                            processedEventRepo.insertIfNotExists(eventId, txId, "CORR-PG-" + index, Instant.now())
                    );
                    results.add(res);
                } catch (Exception e) {
                    results.add(-1);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        long winners = results.stream().filter(r -> r == 1).count();
        long conflicts = results.stream().filter(r -> r == 0).count();

        assertThat(winners).isEqualTo(1);
        assertThat(conflicts).isEqualTo(9);
        assertThat(processedEventRepo.existsById(eventId)).isTrue();
    }

    @Test
    @DisplayName("Should prove transaction rollback removes processed_settlement_events row in real PostgreSQL container")
    void shouldProveRollbackInRealPostgres() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String eventId = "EVT-PG-ROLLBACK-99";
        String txId = "tx-pg-rollback";

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            int inserted = processedEventRepo.insertIfNotExists(eventId, txId, "CORR-RB", Instant.now());
            assertThat(inserted).isEqualTo(1);
            throw new RuntimeException("Simulated Settlement Abort");
        })).isInstanceOf(RuntimeException.class);

        // Assert row does not exist in real PostgreSQL after rollback
        assertThat(processedEventRepo.existsById(eventId)).isFalse();
    }

    @Test
    @DisplayName("Should prove real PostgreSQL optimistic locking collision on LedgerAccountJpaEntity @Version")
    void shouldProveRealPostgresOptimisticLockingCollision() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String accountId = "OMNI:0001:PG-OPT-LOCK";

        txTemplate.execute(status -> accountRepo.save(new LedgerAccountJpaEntity(
                accountId, "Test Pool", com.omniflow.domain.ledger.AccountType.LIABILITY, "USD",
                new BigDecimal("500.00"), false
        )));

        LedgerAccountJpaEntity t1Entity = txTemplate.execute(status -> accountRepo.findById(accountId).orElseThrow());
        LedgerAccountJpaEntity t2Entity = txTemplate.execute(status -> accountRepo.findById(accountId).orElseThrow());

        // T1 commits update
        txTemplate.execute(status -> {
            t1Entity.setBalance(new BigDecimal("600.00"));
            return accountRepo.saveAndFlush(t1Entity);
        });

        // T2 attempts to commit with stale version
        assertThatThrownBy(() -> txTemplate.execute(status -> {
            t2Entity.setBalance(new BigDecimal("700.00"));
            return accountRepo.saveAndFlush(t2Entity);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
