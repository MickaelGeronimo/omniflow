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
5. Account rows use JPA `@Version` optimistic locking so concurrent writes to the same account fail fast rather than overwrite each other.

## Alternatives Considered
* **Single-Entry Ledger (Balance mutation audit log):**
  * *Cons:* Does not enforce conservation of funds across accounts; hard to verify consistency mathematically.
* **Full Event Sourcing:**
  * *Pros:* Complete event history.
  * *Cons:* Rebuilding account balances from scratch requires snapshotting infrastructure. Storing materialized account balances backed by an immutable journal gives us auditability with simple read queries.

## Consequences & Trade-offs
* **Balance Validation:** If debits and credits do not balance, the domain throws `LedgerImbalanceException` before saving to the database.
* **Audit Trail:** Every payment split preserves the complete breakdown across buyer, merchant, platform revenue, and escrow accounts.
