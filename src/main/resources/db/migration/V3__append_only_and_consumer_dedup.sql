-- ====================================================================
-- V3: Consumer Idempotency Store & Append-Only Ledger Immutability
-- ====================================================================

-- 1. Consumer Idempotency Deduplication Store (At-Least-Once SQS Ingestion Guard)
CREATE TABLE IF NOT EXISTS processed_settlement_events (
    event_id VARCHAR(64) PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(64),
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_proc_events_tx ON processed_settlement_events(transaction_id);

-- 2. Correlation ID tracking on Outbox Events
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(64);

-- 3. Append-Only Financial Ledger Guard: Prevent UPDATE or DELETE on posting_legs & journal_entries
CREATE OR REPLACE RULE no_update_posting_legs AS
    ON UPDATE TO posting_legs DO INSTEAD NOTHING;

CREATE OR REPLACE RULE no_delete_posting_legs AS
    ON DELETE TO posting_legs DO INSTEAD NOTHING;

CREATE OR REPLACE RULE no_update_journal_entries AS
    ON UPDATE TO journal_entries DO INSTEAD NOTHING;

CREATE OR REPLACE RULE no_delete_journal_entries AS
    ON DELETE TO journal_entries DO INSTEAD NOTHING;
