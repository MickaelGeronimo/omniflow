-- ====================================================================
-- V2: Financial Invariant Constraints & Outbox Multi-Worker Guards
-- ====================================================================

-- 1. Transaction Constraints
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS chk_tx_amount_positive;
ALTER TABLE transactions ADD CONSTRAINT chk_tx_amount_positive
    CHECK (amount > 0);

ALTER TABLE transactions DROP CONSTRAINT IF EXISTS chk_tx_no_self_transfer;
ALTER TABLE transactions ADD CONSTRAINT chk_tx_no_self_transfer
    CHECK (debtor_account <> creditor_account);

ALTER TABLE transactions DROP CONSTRAINT IF EXISTS chk_tx_currency_format;
ALTER TABLE transactions ADD CONSTRAINT chk_tx_currency_format
    CHECK (char_length(currency) = 3);

-- 2. Ledger Account Constraints
ALTER TABLE ledger_accounts DROP CONSTRAINT IF EXISTS chk_ledger_version_nonneg;
ALTER TABLE ledger_accounts ADD CONSTRAINT chk_ledger_version_nonneg
    CHECK (version >= 0);

-- 3. Posting Leg Constraints
ALTER TABLE posting_legs DROP CONSTRAINT IF EXISTS chk_leg_amount_positive;
ALTER TABLE posting_legs ADD CONSTRAINT chk_leg_amount_positive
    CHECK (amount > 0);

ALTER TABLE posting_legs DROP CONSTRAINT IF EXISTS chk_leg_type;
ALTER TABLE posting_legs ADD CONSTRAINT chk_leg_type
    CHECK (posting_type IN ('DEBIT', 'CREDIT'));

-- 4. Outbox Status Constraint (Staff-Level Multi-Instance State Machine)
ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS chk_outbox_status;
ALTER TABLE outbox_events ADD CONSTRAINT chk_outbox_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED', 'DEAD_LETTER'));

ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS chk_outbox_retry_nonneg;
ALTER TABLE outbox_events ADD CONSTRAINT chk_outbox_retry_nonneg
    CHECK (retry_count >= 0);

-- 5. Idempotency Status Constraint
ALTER TABLE idempotency_keys DROP CONSTRAINT IF EXISTS chk_idemp_status;
ALTER TABLE idempotency_keys ADD CONSTRAINT chk_idemp_status
    CHECK (status IN ('IN_FLIGHT', 'COMPLETED'));
