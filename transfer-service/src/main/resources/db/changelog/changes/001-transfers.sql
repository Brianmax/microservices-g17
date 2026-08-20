--liquibase formatted sql
--changeset virtual-bank:transfer-001
CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    command_id UUID NOT NULL UNIQUE,
    source_account_id UUID NOT NULL,
    destination_account_id UUID NOT NULL,
    source_currency VARCHAR(3),
    destination_currency VARCHAR(3),
    source_amount NUMERIC(19,4) NOT NULL CHECK (source_amount > 0),
    destination_amount NUMERIC(19,4),
    effective_rate NUMERIC(19,8),
    rate_date DATE,
    rate_provider VARCHAR(40),
    reference VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PROCESSING','COMPLETED','FAILED','RETRYABLE')),
    debit_transaction_id UUID,
    credit_transaction_id UUID,
    failure_reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_transfer_request UNIQUE (requester_id,idempotency_key),
    CONSTRAINT ck_transfer_accounts_differ CHECK (source_account_id <> destination_account_id)
);
CREATE INDEX idx_transfers_requester_created ON transfers(requester_id,created_at DESC);
