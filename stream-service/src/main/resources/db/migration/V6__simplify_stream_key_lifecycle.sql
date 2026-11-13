DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM channel_stream_configs WHERE status IN ('PROVISIONING', 'ROTATING')) THEN
        RAISE EXCEPTION 'Finish pending stream key provisioning/reset before applying synchronous lifecycle migration';
    END IF;
END $$;

ALTER TABLE stream_sessions DROP CONSTRAINT fk_stream_sessions_ingest_key;
DROP TABLE stream_media_operations;
DROP TABLE stream_media_runtime_state;
DROP TABLE channel_stream_credentials;
ALTER TABLE channel_stream_configs
    DROP CONSTRAINT ck_channel_stream_configs_credential_state,
    DROP CONSTRAINT ck_channel_stream_configs_generation,
    DROP CONSTRAINT ck_channel_stream_configs_revision,
    DROP CONSTRAINT ck_channel_stream_configs_status,
    DROP COLUMN current_credential_generation,
    DROP COLUMN media_revision,
    ADD CONSTRAINT ck_channel_stream_configs_status CHECK (status IN ('DRAFT', 'ACTIVE', 'BLOCKED')),
    ADD CONSTRAINT ck_channel_stream_configs_ingest_state CHECK (
        (status = 'DRAFT' AND current_ingest_key_id IS NULL)
        OR (status = 'ACTIVE' AND current_ingest_key_id IS NOT NULL AND category_id IS NOT NULL)
        OR status = 'BLOCKED'
    );
