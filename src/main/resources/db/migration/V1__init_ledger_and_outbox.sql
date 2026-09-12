-- ====================================================================
-- V1: OmniFlow Core Baseline Schema
-- Immutable Double-Entry Ledger, Distributed Idempotency & Transactional Outbox
-- ====================================================================

-- 1. Distributed Idempotency Table
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_payload TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS idx_idemp_status ON idempotency_keys(idempotency_key, status);
CREATE INDEX IF NOT EXISTS idx_idemp_expires ON idempotency_keys(expires_at);

-- 2. Core Financial Transactions
CREATE TABLE IF NOT EXISTS transactions (
    transaction_id VARCHAR(64) PRIMARY KEY,
    reference_id VARCHAR(64) UNIQUE NOT NULL,
    debtor_account VARCHAR(64) NOT NULL,
    creditor_account VARCHAR(64) NOT NULL,
    amount NUMERIC(18, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_tx_status_updated ON transactions(status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_tx_debtor ON transactions(debtor_account, created_at DESC);

-- 3. Double-Entry General Ledger Accounts
CREATE TABLE IF NOT EXISTS ledger_accounts (
    account_id VARCHAR(64) PRIMARY KEY,
    account_name VARCHAR(128) NOT NULL,
    account_type VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance NUMERIC(18, 4) NOT NULL DEFAULT 0.0000,
    allow_overdraft BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0
);

-- 4. Journal Entries (Immutable Financial Accounting Batches)
CREATE TABLE IF NOT EXISTS journal_entries (
    entry_id VARCHAR(64) PRIMARY KEY,
    reference_id VARCHAR(64) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    memo TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_journal_ref ON journal_entries(reference_id);

-- 5. Posting Legs (Double-Entry Debit & Credit Records)
CREATE TABLE IF NOT EXISTS posting_legs (
    leg_id BIGSERIAL PRIMARY KEY,
    entry_id VARCHAR(64) REFERENCES journal_entries(entry_id) ON DELETE CASCADE,
    account_id VARCHAR(64) NOT NULL REFERENCES ledger_accounts(account_id),
    posting_type VARCHAR(8) NOT NULL,
    amount NUMERIC(18, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description TEXT
);
CREATE INDEX IF NOT EXISTS idx_legs_entry ON posting_legs(entry_id);
CREATE INDEX IF NOT EXISTS idx_legs_acc_type ON posting_legs(account_id, posting_type);

-- 6. Transactional Outbox Events
CREATE TABLE IF NOT EXISTS outbox_events (
    id VARCHAR(64) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP WITH TIME ZONE,
    locked_by VARCHAR(64),
    locked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS idx_outbox_polling ON outbox_events(status, next_retry_at, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_outbox_locked ON outbox_events(status, locked_at);
