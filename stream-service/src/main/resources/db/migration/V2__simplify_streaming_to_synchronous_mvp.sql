DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM stream_sessions
        WHERE media_path_state IN ('PENDING_OPEN', 'PENDING_CLOSE')
    ) THEN
        RAISE EXCEPTION 'Cannot migrate streaming MVP: verify MediaMTX and resolve PENDING_OPEN/PENDING_CLOSE sessions before retrying migration';
    END IF;
END $$;

ALTER TABLE stream_sessions
    DROP CONSTRAINT ck_stream_sessions_media_state_alignment,
    DROP CONSTRAINT ck_stream_sessions_media_path_state;

ALTER TABLE stream_sessions
    ADD CONSTRAINT ck_stream_sessions_media_path_state
        CHECK (media_path_state IN ('OPEN', 'CLOSED')),
    ADD CONSTRAINT ck_stream_sessions_media_state_alignment CHECK (
        (status IN ('READY', 'LIVE', 'RECONNECTING') AND media_path_state = 'OPEN')
        OR (status = 'ENDED' AND media_path_state = 'CLOSED')
    );

DROP INDEX uq_stream_sessions_channel_slot;
CREATE UNIQUE INDEX uq_stream_sessions_channel_slot
    ON stream_sessions (channel_id)
    WHERE status IN ('READY', 'LIVE', 'RECONNECTING');

DROP TABLE stream_media_sync_tasks;
