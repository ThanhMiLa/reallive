CREATE TABLE streamer_wallets (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    streamer_id UUID NOT NULL,
    available_balance BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_streamer_wallets PRIMARY KEY (id),
    CONSTRAINT uk_streamer_wallets_streamer_id UNIQUE (streamer_id),
    CONSTRAINT ck_streamer_wallets_available_balance CHECK (available_balance >= 0)
);
