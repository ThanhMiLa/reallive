CREATE TABLE virtual_gifts (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    coin_price BIGINT NOT NULL CHECK (coin_price > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_virtual_gifts_name UNIQUE (name)
);

CREATE INDEX idx_virtual_gifts_status ON virtual_gifts (status);

CREATE TABLE donations (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    donor_user_id UUID NOT NULL,
    streamer_id UUID NOT NULL,
    channel_id UUID NOT NULL,
    stream_id UUID NOT NULL,
    gift_id UUID NOT NULL REFERENCES virtual_gifts (id),
    idempotency_key UUID NOT NULL,
    coin_price BIGINT NOT NULL CHECK (coin_price > 0),
    status VARCHAR(30) NOT NULL CHECK (status IN ('COMPLETED', 'REFUNDED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_donations_donor_idempotency UNIQUE (donor_user_id, idempotency_key)
);

CREATE INDEX idx_donations_donor_created_at ON donations (donor_user_id, created_at DESC, id DESC);
CREATE INDEX idx_donations_streamer_created_at ON donations (streamer_id, created_at DESC, id DESC);
CREATE INDEX idx_donations_stream_created_at ON donations (stream_id, created_at DESC, id DESC);
CREATE INDEX idx_donations_channel_id ON donations (channel_id);
CREATE INDEX idx_donations_gift_id ON donations (gift_id);
