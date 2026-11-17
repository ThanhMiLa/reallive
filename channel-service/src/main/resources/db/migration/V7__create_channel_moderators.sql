CREATE TABLE channel_moderators (
    channel_id UUID NOT NULL REFERENCES channels(id),
    user_id UUID NOT NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (channel_id, user_id)
);
CREATE INDEX idx_channel_moderators_user_id ON channel_moderators (user_id);
