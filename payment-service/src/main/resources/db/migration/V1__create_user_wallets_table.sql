CREATE TABLE user_wallets (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_wallets PRIMARY KEY (id),
    CONSTRAINT uk_user_wallets_user_id UNIQUE (user_id),
    CONSTRAINT ck_user_wallets_balance CHECK (balance >= 0)
);
