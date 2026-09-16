# Architecture Decision Records (ADRs) — OmniFlow

This directory contains the formal Architecture Decision Records for **OmniFlow**, documenting the rationale, trade-offs, and invariants governing marketplace splits, ledger consistency, and asynchronous settlement.

| ADR | Title | Status | Scope |
| :--- | :--- | :--- | :--- |
| [ADR-001](ADR-001-transactional-outbox-skip-locked.md) | Transactional Outbox Pattern with PostgreSQL `SKIP LOCKED` | Accepted | Dual-Write Elimination (AWS SNS/SQS) |
| [ADR-002](ADR-002-double-entry-general-ledger.md) | Double-Entry General Ledger with Zero-Sum Invariant | Accepted | 4-Leg Marketplace Splits & $\sum D = \sum C$ |
| [ADR-003](ADR-003-polyglot-persistence-postgres-mongodb.md) | Polyglot Persistence (PostgreSQL + MongoDB) | Accepted | Workload Isolation (ACID vs Append-Only Logs) |
| [ADR-004](ADR-004-deterministic-ai-agent-guardrails.md) | Incident Triage & Policy Guardrails (HITL) | Accepted | Poison-Pill DLQ Triage & Safe Execution |
| [ADR-005](ADR-005-sha256-distributed-idempotency.md) | SHA-256 Distributed Idempotency (Anti-Phantom Success) | Accepted | Concurrency Protection & Strict Scope (`REQUIRED`) |
| [ADR-006](ADR-006-reconciliation-keyset-pagination-and-lease-recovery.md) | Keyset Pagination, Outbox Lease Recovery & Incident Reasoning | Accepted | Bounded Memory Batching ($O(1)$) & Crash Recovery |
