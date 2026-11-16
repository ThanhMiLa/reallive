DELETE FROM channel_user_bans
WHERE id NOT IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY channel_id, user_id ORDER BY
            CASE ban_type WHEN 'FULL_BAN' THEN 3 WHEN 'LIVE_VIEW_BAN' THEN 2 ELSE 1 END DESC) AS priority
        FROM (
            SELECT id, channel_id, user_id, ban_type, action,
                ROW_NUMBER() OVER (PARTITION BY channel_id, user_id, ban_type ORDER BY created_at DESC, event_order DESC) AS latest
            FROM channel_user_bans
        ) events
        WHERE latest = 1 AND action = 'BAN'
    ) active_bans WHERE priority = 1
);
DELETE FROM channel_user_bans WHERE EXISTS (
    SELECT 1 FROM channels WHERE channels.id = channel_user_bans.channel_id
      AND channels.user_id = channel_user_bans.user_id
);
DROP INDEX idx_channel_user_bans_current;
ALTER TABLE channel_user_bans DROP COLUMN action;
ALTER TABLE channel_user_bans DROP COLUMN event_order;
ALTER TABLE channel_user_bans ADD CONSTRAINT uk_channel_user_bans UNIQUE (channel_id, user_id);
CREATE INDEX idx_channel_user_bans_listing ON channel_user_bans (channel_id, created_at DESC, id DESC);
