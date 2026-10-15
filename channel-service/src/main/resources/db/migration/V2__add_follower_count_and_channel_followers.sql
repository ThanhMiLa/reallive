ALTER TABLE channels
    ADD COLUMN follower_count BIGINT NOT NULL DEFAULT 0;

ALTER TABLE channels
    ADD CONSTRAINT ck_channels_follower_count CHECK (follower_count >= 0);

CREATE TABLE channel_followers (
    channel_id UUID NOT NULL,
    user_id UUID NOT NULL,
    followed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_channel_followers PRIMARY KEY (channel_id, user_id),
    CONSTRAINT fk_channel_followers_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
);

CREATE INDEX idx_channel_followers_user_id ON channel_followers (user_id);
