# OmniFlow — Architecture Diagrams

## C4 Level 1 — System Context

```mermaid
C4Context
    title System Context — OmniFlow Platform

    Person(client, "Marketplace Platform / Client App", "Submits checkout payments and splits via REST API")
    
    System(omniflow, "OmniFlow Core", "Multi-party ledger orchestration, transactional outbox, and batch reconciliation")
    
    System_Ext(aws_sns, "AWS SNS / SQS", "Event fan-out and async settlement queue")
    System_Ext(aws_s3, "AWS S3", "Nightly financial audit reports storage")
    SystemDb_Ext(postgres, "PostgreSQL 16", "Relational double-entry ledger & transactional outbox")
    SystemDb_Ext(mongo, "MongoDB 7.0", "Immutable audit event logs and incident triage evidence")
    System_Ext(prometheus, "Prometheus / Grafana", "Metrics collection and SLO dashboards")

    Rel(client, omniflow, "POST /api/v1/transactions", "HTTPS + JWT / X-API-KEY")
    Rel(omniflow, postgres, "ACID Units of Work", "JDBC")
    Rel(omniflow, aws_sns, "Publishes settled events", "AWS SDK v2")
    Rel(aws_sns, omniflow, "Consumes settlement queue", "SQS Listener")
    Rel(omniflow, mongo, "Persists audit trail & DLQ triage", "Mongo Driver")
    Rel(omniflow, aws_s3, "Uploads reconciliation reports", "AWS S3 SDK")
    Rel(prometheus, omniflow, "Scrapes /actuator/prometheus", "HTTP")
```

---

## C4 Level 2 — Container Diagram

```mermaid
C4Container
    title Container Diagram — OmniFlow Core

    Person(client, "Marketplace Client")
    
    Container(api, "Financial Web API", "Spring Boot / Java 17", "Accepts transactions, enforces auth, rate limiting, and SHA-256 idempotency")
    Container(relay, "Outbox Relay Worker", "Spring Scheduling", "Polls PENDING outbox events with SKIP LOCKED and publishes to AWS SNS")
    Container(consumer, "SQS Settlement Consumer", "Spring JMS / AWS SQS", "Consumes settlement events, enforces deduplication, writes audit logs")
    Container(batch, "Reconciliation Batch Job", "Spring Batch 5", "Cursor keyset pagination auditing million-record ledgers")

    ContainerDb(db, "PostgreSQL 16", "Relational DB", "Accounts, journal entries, posting legs, outbox events, idempotency keys")
    ContainerDb(mongo, "MongoDB 7.0", "Document DB", "Audit events, triage diagnostic logs")
    Container_Ext(localstack, "AWS Infrastructure", "LocalStack / AWS Cloud", "SNS Topics, SQS Queues, S3 Buckets")

    Rel(client, api, "POST /api/v1/transactions", "HTTPS")
    Rel(api, db, "Atomic 4-leg split commit", "JDBC")
    Rel(relay, db, "SELECT ... FOR UPDATE SKIP LOCKED", "JDBC")
    Rel(relay, localstack, "Publish to SNS Topic", "HTTPS")
    Rel(localstack, consumer, "Deliver message from SQS", "HTTPS")
    Rel(consumer, mongo, "Append audit record", "Mongo Protocol")
    Rel(consumer, db, "Insert processed_settlement_events", "JDBC")
    Rel(batch, db, "Keyset cursor read (O(1))", "JDBC")
    Rel(batch, localstack, "Write reconciliation report to S3", "HTTPS")
```

---

## C4 Level 3 — Component Diagram (Hexagonal Architecture)

```mermaid
graph TB
    subgraph "Inbound Adapters [adapter/in]"
        CTRL["TransactionController\n(REST / Spring MVC)"]
        AUTH["SecurityConfig\n(JWT + ApiKeyAuthenticationFilter)"]
        RATE["RateLimitingFilter\n(Bucket4j Anti-OOM)"]
        CORR["CorrelationIdFilter\n(MDC Tracing)"]
        EX_HANDLER["GlobalExceptionHandler\n(ProblemDetail RFC 7807)"]
    end

    subgraph "Application Layer [application]"
        TX_USECASE["SubmitTransactionUseCase\n(port/in)"]
        RECON_USECASE["ReconcileLedgerUseCase\n(port/in)"]
        ORCHESTRATOR["FinancialOrchestratorService\n(@Transactional REQUIRED)"]
        TRIAGE_SVC["IncidentTriageService\n(application/service)"]
    end

    subgraph "Domain Layer [domain]"
        LEDGER_ACC["LedgerAccount\n(@Version Optimistic Lock)"]
        JOURNAL_ENTRY["JournalEntry\n(4-Leg Balanced Split)"]
        POSTING_LEG["PostingLeg\n(CREDIT / DEBIT)"]
        MONEY["Money\n(Strict BigDecimal 4dp)"]
    end

    subgraph "Outbound Ports [application/port/out]"
        TX_PORT["TransactionRepositoryPort"]
        LEDGER_PORT["LedgerRepositoryPort"]
        OUTBOX_PORT["OutboxRepositoryPort"]
        IDEMP_PORT["IdempotencyStoragePort"]
        SNS_PORT["SnsPublisherPort"]
        AUDIT_PORT["AuditEventStorePort"]
        S3_PORT["S3StoragePort"]
    end

    subgraph "Outbound Adapters [adapter/out]"
        PG_TX["PostgresTransactionRepositoryAdapter"]
        PG_LEDGER["PostgresLedgerRepositoryAdapter"]
        PG_OUTBOX["PostgresOutboxRepositoryAdapter"]
        PG_IDEMP["PostgresIdempotencyStorageAdapter"]
        SNS_ADAPTER["SnsEventPublisherAdapter"]
        MONGO_AUDIT["MongoAuditEventStoreAdapter"]
        S3_ADAPTER["S3ReconciliationStorageAdapter"]
    end

    CTRL --> TX_USECASE
    TX_USECASE --> ORCHESTRATOR
    ORCHESTRATOR --> LEDGER_ACC & JOURNAL_ENTRY
    ORCHESTRATOR --> TX_PORT & LEDGER_PORT & OUTBOX_PORT & IDEMP_PORT
    
    TX_PORT --> PG_TX
    LEDGER_PORT --> PG_LEDGER
    OUTBOX_PORT --> PG_OUTBOX
    IDEMP_PORT --> PG_IDEMP
    SNS_PORT --> SNS_ADAPTER
    AUDIT_PORT --> MONGO_AUDIT
    S3_PORT --> S3_ADAPTER
```

---

## 4-Leg Marketplace Split Accounting Sequence

```mermaid
sequenceDiagram
    participant Client as Client Application
    participant API as TransactionController
    participant Orchestrator as FinancialOrchestratorService
    participant Ledger as LedgerAccount (@Version)
    participant DB as PostgreSQL (ACID)

    Note over Client,API: POST /api/v1/transactions ($1,250.00 Gross)
    Client->>API: submitTransaction(command, idempotencyKey)
    API->>Orchestrator: executeTransaction(command)
    
    Note over Orchestrator,DB: Single @Transactional (REQUIRED) Boundary
    Orchestrator->>DB: Check & acquire SHA-256 Idempotency lease
    Orchestrator->>Ledger: Apply Leg 1: Debit Buyer -$1,250.00 (Liability)
    Orchestrator->>Ledger: Apply Leg 2: Credit Merchant +$1,162.50 (Liability)
    Orchestrator->>Ledger: Apply Leg 3: Credit Platform +$62.50 (Revenue)
    Orchestrator->>Ledger: Apply Leg 4: Credit Escrow +$25.00 (Liability)
    
    Note over Ledger: Verify Invariant: Debits ($1250) == Credits ($1162.50 + $62.50 + $25.00)
    Orchestrator->>DB: INSERT INTO journal_entries & posting_legs
    Orchestrator->>DB: UPDATE ledger_accounts (versions incremented)
    Orchestrator->>DB: INSERT INTO transactions (status=FUNDS_RESERVED)
    Orchestrator->>DB: INSERT INTO outbox_events (status=PENDING)
    DB-->>Orchestrator: Atomic Commit ✅
    Orchestrator-->>API: TransactionResponseDto (FUNDS_RESERVED)
    API-->>Client: 201 Created
```

---

## Transactional Outbox & AWS SNS/SQS Delivery

```mermaid
sequenceDiagram
    participant Relay as OutboxRelayScheduledWorker
    participant DB as PostgreSQL
    participant SNS as AWS SNS Topic
    participant SQS as AWS SQS Queue
    participant Consumer as SqsSettlementConsumer
    participant Mongo as MongoDB

    Note over Relay,DB: Runs every fixedDelay with SKIP LOCKED
    Relay->>DB: SELECT * FROM outbox_events WHERE status IN ('PENDING','FAILED') FOR UPDATE SKIP LOCKED
    Relay->>SNS: publish(eventJson)
    SNS->>SQS: Fan-out to settlement queue
    SNS-->>Relay: MessageId returned
    Relay->>DB: UPDATE outbox_events SET status='PUBLISHED', published_at=NOW()

    Note over SQS,Consumer: Asynchronous consumer processing
    SQS->>Consumer: onMessage(eventPayload)
    Consumer->>DB: INSERT INTO processed_settlement_events (event_id)
    alt New Event (Idempotent Deduplication)
        Consumer->>Mongo: saveAuditEvent(auditDocument)
        Consumer-->>SQS: ACK message deleted
    else Duplicate Event (Already Processed)
        Consumer-->>SQS: ACK ignored (Zero Duplicate Impact)
    end
```
