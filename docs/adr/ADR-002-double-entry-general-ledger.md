# ADR-002: Double-Entry General Ledger with Zero-Sum Invariant

## Status
**Accepted**

## Context
When account balances are stored only as a single mutable column (e.g. `UPDATE accounts SET balance = balance - 100`), concurrent updates and partial failures make it difficult to audit where money moved or verify consistency across accounts.

Marketplace models also require multi-party settlement splits where a single payment is divided into:
1. Buyer debit
2. Merchant payout
3. Platform commission fee
4. Escrow reserve for chargeback protection

## Decision
We implemented a **Double-Entry General Ledger** domain model:
1. Balances are not modified directly. Every financial operation creates an immutable `JournalEntry` with two or more `PostingLeg` records.
2. The domain model enforces the **zero-sum balance invariant**:
   $$\sum \text{Debits} = \sum \text{Credits}$$
3. Account types follow standard accounting equations:
   * **ASSET / EXPENSE:** Debits increase balance; Credits decrease balance.
   * **LIABILITY / EQUITY / REVENUE:** Credits increase balance; Debits decrease balance.
4. Monetary values are modeled as an immutable `Money` value object with 4 decimal places of precision (`RoundingMode.HALF_EVEN`) to avoid floating-point errors.
5. Optimistic concurrency control (`@Version`) on `ledger_accounts` prevents lost updates under concurrent transactions.

## Alternatives Considered
* **Single-Entry Ledger (Balance mutation audit log):**
  * *Cons:* Does not enforce conservation of funds across accounts; hard to verify consistency mathematically.
* **Full Event Sourcing:**
  * *Pros:* Complete event history.
  * *Cons:* Rebuilding account balances across large posting volumes requires a snapshotting pipeline. We combine an immutable journal with materialized account balance records protected by `@Version`.

## Consequences & Trade-offs
* **Balance Validation:** If $\sum \text{Debits} \neq \sum \text{Credits}$, a `LedgerImbalanceException` is thrown before any record is saved.
* **Auditability:** Complete chronological record of every split across buyer, merchant, platform revenue, and escrow accounts.
