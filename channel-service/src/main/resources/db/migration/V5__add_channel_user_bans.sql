CREATE TABLE channel_user_bans (
    id UUID PRIMARY KEY,
    event_order BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    channel_id UUID NOT NULL REFERENCES channels(id),
    user_id UUID NOT NULL,
    action VARCHAR(20) NOT NULL CHECK (action IN ('BAN', 'UNBAN')),
    ban_type VARCHAR(30) NOT NULL CHECK (ban_type IN ('CHAT_BAN', 'LIVE_VIEW_BAN', 'FULL_BAN')),
    performed_by UUID NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_channel_user_bans_current
    ON channel_user_bans (channel_id, user_id, ban_type, created_at DESC, event_order DESC);
