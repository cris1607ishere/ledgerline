-- LedgerLine schema
-- Money is stored as NUMERIC, never FLOAT/DOUBLE.

CREATE TABLE accounts (
    id          UUID PRIMARY KEY,
    owner_name  VARCHAR(255) NOT NULL,
    currency    VARCHAR(3) NOT NULL,
    balance     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only. Application code must never UPDATE or DELETE a row here.
CREATE TABLE ledger_transactions (
    id                   UUID PRIMARY KEY,
    idempotency_key      VARCHAR(255) NOT NULL UNIQUE,
    source_account_id    UUID NOT NULL REFERENCES accounts(id),
    destination_account_id UUID NOT NULL REFERENCES accounts(id),
    amount               NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency             VARCHAR(3) NOT NULL,
    status               VARCHAR(20) NOT NULL,
    previous_hash        VARCHAR(64) NOT NULL,
    hash                 VARCHAR(64) NOT NULL UNIQUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only. Every transaction produces exactly two rows whose amounts sum to zero.
CREATE TABLE ledger_entries (
    id             UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transactions(id),
    account_id     UUID NOT NULL REFERENCES accounts(id),
    amount         NUMERIC(19, 4) NOT NULL, -- signed: negative for DEBIT, positive for CREDIT
    entry_type     VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_entries_account_id ON ledger_entries(account_id);
CREATE INDEX idx_ledger_transactions_source ON ledger_transactions(source_account_id);
CREATE INDEX idx_ledger_transactions_destination ON ledger_transactions(destination_account_id);

CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    transaction_id  UUID NOT NULL REFERENCES ledger_transactions(id),
    status          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Singleton row (id is always 1) holding the tip of the hash chain.
-- Locked with SELECT ... FOR UPDATE on every transaction to append safely under concurrency.
CREATE TABLE chain_state (
    id        SMALLINT PRIMARY KEY DEFAULT 1,
    last_hash VARCHAR(64) NOT NULL,
    CONSTRAINT chain_state_singleton CHECK (id = 1)
);

INSERT INTO chain_state (id, last_hash) VALUES (1, repeat('0', 64));

-- A couple of seed accounts so you can hit the API immediately.
INSERT INTO accounts (id, owner_name, currency, balance) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Alice', 'INR', 1000.00),
    ('22222222-2222-2222-2222-222222222222', 'Bob',   'INR', 500.00);
