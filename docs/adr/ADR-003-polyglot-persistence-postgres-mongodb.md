# ADR-003: Polyglot Persistence (PostgreSQL + MongoDB)

## Status
**Accepted**

## Context
The platform has two different data access patterns:
1. **Financial Transactions & Ledger:** Requires ACID transactions, foreign keys, row versioning, and constraint checks to prevent inconsistencies.
2. **Audit Trails & Incident Triage Logs:** High-volume, append-only semi-structured data (event payloads, tool execution outputs, stack traces) that should not compete for locks or connection pool capacity with ledger operations.

## Decision
We use a **Polyglot Persistence** model:
* **PostgreSQL 16:** Relational data requiring ACID transactions:
  * `transactions`
  * `ledger_accounts`
  * `journal_entries` & `posting_legs`
  * `idempotency_keys`
  * `outbox_events`
* **MongoDB 7.0:** Append-only audit documents:
  * `audit_events`: Full payloads saved asynchronously by message consumers.
  * `agent_triage_logs`: Diagnostic outputs, tool execution results, confidence scores, and triage evidence.

## Alternatives Considered
* **PostgreSQL JSONB for everything:**
  * *Cons:* Storing verbose audit payloads and diagnostic logs in PostgreSQL increases table size and cache usage, competing for connection pool resources with core payment operations.
* **MongoDB for everything:**
  * *Cons:* Multi-document transactions in MongoDB are less practical for enforcing double-entry constraints, and MongoDB lacks PostgreSQL features like `SKIP LOCKED`.

## Consequences & Trade-offs
* **Workload Separation:** Audit writes do not contend with ledger transactions for database locks or connection pool capacity.
* **Schema Flexibility:** Diagnostic logs and payloads can evolve without running relational schema migrations.
