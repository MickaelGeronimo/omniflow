# ADR-003: Polyglot Persistence (PostgreSQL + MongoDB)

## Status
**Accepted**

## Context
We have two very different data access patterns in the system:
1. **Financial Transactions & Ledger:** Requires ACID transactions, foreign keys, row versioning, and constraints to prevent inconsistencies.
2. **Audit Trails & Incident Triage Logs:** High-volume, append-only JSON documents (event payloads, tool execution outputs, stack traces) that should not compete for database connections or locks with payment processing.

## Decision
We split persistence between PostgreSQL and MongoDB:
* **PostgreSQL 16:** Relational data requiring ACID transactions:
  * `transactions`
  * `ledger_accounts`
  * `journal_entries` & `posting_legs`
  * `idempotency_keys`
  * `outbox_events`
* **MongoDB 7.0:** Append-only audit documents:
  * `audit_events`: Full event payloads saved asynchronously by message consumers.
  * `agent_triage_logs`: Diagnostic outputs, tool execution results, confidence scores, and triage evidence.

## Alternatives Considered
* **PostgreSQL JSONB for everything:**
  * *Cons:* Writing large audit payloads to Postgres bloats table storage and connection pool usage during payment bursts.
* **MongoDB for everything:**
  * *Cons:* MongoDB does not provide the relational constraints or `SKIP LOCKED` polling we use for the ledger and outbox.

## Consequences & Trade-offs
* **Workload Isolation:** Audit writes run against MongoDB without holding locks or pool connections on PostgreSQL.
* **Schema Flexibility:** Audit payloads and triage diagnostic logs can change format without schema migrations.
