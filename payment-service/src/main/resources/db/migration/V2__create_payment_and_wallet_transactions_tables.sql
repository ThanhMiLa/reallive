CREATE TABLE wallet_transactions (
    id UUID PRIMARY KEY,
    wallet_id UUID NOT NULL REFERENCES user_wallets (id),
    type VARCHAR(30) NOT NULL,
    amount BIGINT NOT NULL,
    reference_id UUID,
    created_at TIMESTAMP NOT NULL,
    CHECK (type IN ('DEPOSIT', 'GIFT_PURCHASE', 'REFUND')),
    CHECK (amount > 0),
    CHECK (type <> 'DEPOSIT' OR reference_id IS NOT NULL),
    CONSTRAINT uk_wallet_transactions_type_reference UNIQUE (type, reference_id)
);

CREATE INDEX idx_wallet_transactions_wallet_created_at ON wallet_transactions (wallet_id, created_at DESC);

CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY,
    wallet_id UUID NOT NULL REFERENCES user_wallets (id),
    order_code BIGINT NOT NULL,
    provider VARCHAR(30) NOT NULL,
    package_code VARCHAR(50) NOT NULL,
    amount BIGINT NOT NULL,
    coin_amount BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    provider_create_date VARCHAR(14) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    provider_transaction_no VARCHAR(64),
    provider_response_code VARCHAR(10),
    provider_transaction_status VARCHAR(10),
    paid_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_payment_transactions_order_code UNIQUE (order_code),
    CONSTRAINT uk_payment_transactions_provider_tx_no UNIQUE (provider, provider_transaction_no),
    CHECK (provider IN ('VNPAY')),
    CHECK (amount > 0),
    CHECK (coin_amount > 0),
    CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CANCELLED')),
    CHECK (status <> 'PAID' OR paid_at IS NOT NULL),
    CHECK (provider_transaction_no IS NULL OR (length(btrim(provider_transaction_no)) > 0 AND provider_transaction_no <> '0'))
);

CREATE INDEX idx_payment_transactions_status_created_expires ON payment_transactions (status, created_at, expires_at);

CREATE INDEX idx_payment_transactions_wallet_created_at ON payment_transactions (wallet_id, created_at DESC);
