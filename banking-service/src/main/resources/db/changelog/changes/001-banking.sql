--liquibase formatted sql
--changeset virtual-bank:banking-001
CREATE TABLE accounts (
 id UUID PRIMARY KEY, account_number VARCHAR(20) NOT NULL UNIQUE, owner_id UUID NOT NULL,
 account_type VARCHAR(20) NOT NULL CHECK(account_type IN ('CHECKING','SAVINGS')),
 currency VARCHAR(3) NOT NULL CHECK(currency IN ('USD','EUR','PEN')),
 balance NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK(balance >= 0),
 status VARCHAR(20) NOT NULL CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
 version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_accounts_owner ON accounts(owner_id);
CREATE TABLE transactions (
 id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES accounts(id),
 transaction_type VARCHAR(20) NOT NULL CHECK(transaction_type IN ('DEPOSIT','WITHDRAWAL','TRANSFER_IN','TRANSFER_OUT')),
 amount NUMERIC(19,4) NOT NULL CHECK(amount>0), balance_after NUMERIC(19,4) NOT NULL CHECK(balance_after>=0),
 reference VARCHAR(64) NOT NULL, description VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_transactions_account_created ON transactions(account_id,created_at DESC);
CREATE TABLE posted_commands(command_id UUID PRIMARY KEY, source_transaction_id UUID NOT NULL, destination_transaction_id UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL);
