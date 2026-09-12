# ADR-003: Polyglot Persistence Architecture (PostgreSQL + MongoDB)

## Status
**Accepted**

## Context
Financial orchestration platforms have conflicting data access patterns:
1. **Core Financial Transactions & Double-Entry Ledger:** Requires strict ACID guarantees, foreign key constraints, row versioning, and serializability to prevent double spending.
2. **System Audit Trails & AI Incident Reasoning Trails:** Generates write-heavy, semi-structured documents (dynamic tool outputs, raw JSON payloads, stack traces, and forensic evidence) that should be append-only and not compete for I/O locks with transactional ledger tables.

## Decision
We adopted a **Polyglot Persistence** model:
* **PostgreSQL 16:** Dedicated to relational, ACID-critical data structures:
  * `transactions`
  * `ledger_accounts`
  * `journal_entries` & `posting_legs`
  * `idempotency_keys`
  * `outbox_events`
* **MongoDB 7.0:** Dedicated to append-only, schemaless audit and AI logs:
  * `audit_events`: Full lifecycle payloads emitted asynchronously by message consumers.
  * `agent_triage_logs`: Diagnostic reasoning trails, tool invocation metrics, confidence scores, and forensic evidence collected during automated incident triage.

## Alternatives Considered
* **PostgreSQL JSONB for everything:**
  * *Cons:* Storing semi-structured diagnostic logs and high-frequency audit events in PostgreSQL bloats the relational buffer cache and tablespace, competing for connection pool resources during ledger operations.
* **MongoDB for everything:**
  * *Cons:* MongoDB multi-document transactions introduce latency and lack the strict relational integrity checks, declarative constraints, and `SKIP LOCKED` semantics of PostgreSQL.

## Consequences & Trade-offs
* **Workload Isolation:** Relational transactions maintain maximum throughput and minimal latency without lock contention from audit ingestion.
* **Flexible Forensics:** The AI Agent can persist arbitrary diagnostic evidence objects into MongoDB without requiring schema migrations.
