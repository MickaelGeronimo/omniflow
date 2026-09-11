# ADR-002: Double-Entry General Ledger with Mathematical Zero-Sum Invariant

## Status
**Accepted**

## Context
Financial platforms commonly fail audits when account balances are modeled as simple mutable columns (e.g. `UPDATE accounts SET balance = balance - 100`). Under concurrent updates or network errors, balances drift, funds "evaporate" or are created out of thin air, and there is no auditable accounting trail of where money came from or went.

Furthermore, marketplace business models require multi-party settlement splits where a single customer payment must be atomically divided into:
1. Gross Acquirer/Buyer debit
2. Merchant net payout
3. Platform take-rate commission fee
4. Escrow reserve retention for chargeback liability

## Decision
We implemented a strict **Double-Entry General Ledger** domain model:
1. Balances are never arbitrarily edited. Every financial event creates an immutable `JournalEntry` composed of two or more `PostingLeg` records.
2. The domain model strictly enforces the mathematical **zero-sum balance invariant**:
   $$\sum \text{Debits} = \sum \text{Credits}$$
3. Account types follow GAAP accounting equations:
   * **ASSET / EXPENSE:** Debits *increase* balance; Credits *decrease* balance.
   * **LIABILITY / EQUITY / REVENUE:** Credits *increase* balance; Debits *decrease* balance.
4. Monetary values are modeled as an immutable `Money` Value Object with 4 decimal places of precision (`RoundingMode.HALF_EVEN`) to eliminate floating-point rounding errors.
5. Optimistic concurrency control (`@Version`) is enforced on `ledger_accounts` to prevent concurrent lost updates.

## Alternatives Considered
* **Single-Entry Ledger (Audit Log of balance changes):**
  * *Cons:* Does not enforce conservation of money across the system; impossible to detect system-wide financial leaks mathematically.
* **Event Sourcing (Full rebuild of state from scratch):**
  * *Pros:* Complete history.
  * *Cons:* Rebuilding account balances with millions of postings requires complex snapshotting architectures. Our approach combines an immutable journal with materialized, snapshot-safe account records locked via `@Version`.

## Consequences & Trade-offs
* **Absolute Financial Integrity:** If $\sum \text{Debits} \neq \sum \text{Credits}$, a `LedgerImbalanceException` is thrown before any record touches the database.
* **Auditability:** Complete chronological trail of every split cent across buyer, merchant, platform revenue, and escrow reserves.
