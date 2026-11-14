DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM stream_sessions
        WHERE status IN ('READY', 'LIVE', 'RECONNECTING')
    ) THEN
        RAISE EXCEPTION 'Cannot migrate to persistent stream keys: resolve active READY, LIVE, or RECONNECTING stream sessions before migrating';
    END IF;
END $$;

CREATE TABLE channel_stream_configs (
    channel_id UUID NOT NULL,
    streamer_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    category_id UUID,
    visibility VARCHAR(30) NOT NULL DEFAULT 'PUBLIC',
    status VARCHAR(30) NOT NULL,
    current_ingest_key_id UUID,
    current_credential_generation BIGINT NOT NULL DEFAULT 0,
    media_revision BIGINT NOT NULL DEFAULT 0,
    blocked_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_channel_stream_configs PRIMARY KEY (channel_id),
    CONSTRAINT uk_channel_stream_configs_owner UNIQUE (owner_user_id),
    CONSTRAINT uk_channel_stream_configs_streamer UNIQUE (streamer_id),
    CONSTRAINT uk_channel_stream_configs_ingest_key UNIQUE (current_ingest_key_id),
    CONSTRAINT ck_channel_stream_configs_title_not_blank CHECK (status = 'DRAFT' OR length(btrim(title)) > 0),
    CONSTRAINT ck_channel_stream_configs_visibility CHECK (visibility = 'PUBLIC'),
    CONSTRAINT ck_channel_stream_configs_status CHECK (
        status IN ('DRAFT', 'PROVISIONING', 'ACTIVE', 'ROTATING', 'BLOCKED')
    ),
    CONSTRAINT ck_channel_stream_configs_generation CHECK (current_credential_generation >= 0),
    CONSTRAINT ck_channel_stream_configs_revision CHECK (media_revision >= 0),
    CONSTRAINT ck_channel_stream_configs_blocked_reason CHECK (status = 'BLOCKED' OR blocked_reason IS NULL),
    CONSTRAINT ck_channel_stream_configs_credential_state CHECK (
        (status = 'DRAFT' AND current_ingest_key_id IS NULL AND current_credential_generation = 0)
        OR (status = 'BLOCKED' AND current_ingest_key_id IS NULL AND current_credential_generation = 0)
        OR (status <> 'DRAFT' AND category_id IS NOT NULL AND current_ingest_key_id IS NOT NULL AND current_credential_generation > 0)
    )
);

CREATE TABLE channel_stream_credentials (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    channel_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    ingest_key_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    encrypted_publish_jwt TEXT NOT NULL,
    encrypted_relay_read_jwt TEXT NOT NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMP,
    revoked_at TIMESTAMP,
    revocation_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_channel_stream_credentials PRIMARY KEY (id),
    CONSTRAINT fk_channel_stream_credentials_channel FOREIGN KEY (channel_id)
        REFERENCES channel_stream_configs (channel_id) ON DELETE CASCADE,
    CONSTRAINT uk_channel_stream_credentials_channel_gen UNIQUE (channel_id, generation),
    CONSTRAINT uk_channel_stream_credentials_ingest_key UNIQUE (ingest_key_id),
    CONSTRAINT ck_channel_stream_credentials_generation CHECK (generation > 0),
    CONSTRAINT ck_channel_stream_credentials_status CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED')),
    CONSTRAINT ck_channel_stream_credentials_publish_jwt CHECK (length(btrim(encrypted_publish_jwt)) > 0),
    CONSTRAINT ck_channel_stream_credentials_relay_jwt CHECK (length(btrim(encrypted_relay_read_jwt)) > 0),
    CONSTRAINT ck_channel_stream_credentials_revoked CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (status <> 'REVOKED' AND revoked_at IS NULL AND revocation_reason IS NULL)
    ),
    CONSTRAINT ck_channel_stream_credentials_activated CHECK (
        (status = 'ACTIVE' AND activated_at IS NOT NULL) OR (status <> 'ACTIVE')
    )
);

CREATE UNIQUE INDEX uq_channel_stream_credentials_non_revoked
    ON channel_stream_credentials (channel_id)
    WHERE status IN ('PENDING', 'ACTIVE');

CREATE INDEX idx_channel_stream_credentials_channel_status
    ON channel_stream_credentials (channel_id, status, generation DESC);

ALTER TABLE stream_sessions
    DROP CONSTRAINT ck_stream_sessions_status,
    DROP CONSTRAINT ck_stream_sessions_media_state_alignment;

ALTER TABLE stream_sessions
    ALTER COLUMN creation_request_id DROP NOT NULL,
    ADD COLUMN ingest_key_id UUID,
    ADD COLUMN end_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN end_requested_at TIMESTAMP,
    ADD COLUMN end_reason VARCHAR(500);

ALTER TABLE stream_sessions
    ADD CONSTRAINT fk_stream_sessions_ingest_key FOREIGN KEY (ingest_key_id)
        REFERENCES channel_stream_credentials (ingest_key_id),
    ADD CONSTRAINT ck_stream_sessions_status CHECK (status IN ('LIVE', 'RECONNECTING', 'ENDED')),
    ADD CONSTRAINT ck_stream_sessions_media_state_alignment CHECK (
        (status = 'LIVE' AND media_path_state = 'OPEN')
        OR (status = 'RECONNECTING' AND media_path_state IN ('OPEN', 'CLOSED'))
        OR (status = 'ENDED' AND media_path_state = 'CLOSED')
    ),
    ADD CONSTRAINT ck_stream_sessions_end_requested CHECK (
        (end_requested = FALSE AND end_requested_at IS NULL AND end_reason IS NULL)
        OR (end_requested = TRUE AND end_requested_at IS NOT NULL)
    );

DROP INDEX uq_stream_sessions_channel_slot;
CREATE UNIQUE INDEX uq_stream_sessions_channel_slot
    ON stream_sessions (channel_id)
    WHERE status IN ('LIVE', 'RECONNECTING');

CREATE INDEX idx_stream_sessions_ingest_key_id
    ON stream_sessions (ingest_key_id)
    WHERE ingest_key_id IS NOT NULL;

CREATE TABLE stream_media_operations (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    channel_id UUID NOT NULL,
    credential_generation BIGINT NOT NULL,
    media_revision BIGINT NOT NULL,
    operation_type VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    target_session_id UUID,
    candidate_session_id UUID,
    target_ingest_key_id UUID,
    target_source_id UUID,
    target_source_type VARCHAR(30),
    deduplication_key VARCHAR(200),
    payload TEXT,
    last_error VARCHAR(500),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until TIMESTAMP,
    lease_owner VARCHAR(100),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_stream_media_operations PRIMARY KEY (id),
    CONSTRAINT fk_stream_media_operations_channel FOREIGN KEY (channel_id)
        REFERENCES channel_stream_configs (channel_id) ON DELETE CASCADE,
    CONSTRAINT ck_stream_media_operations_type CHECK (
        operation_type IN (
            'PROVISION_INGEST',
            'PROCESS_ONLINE',
            'PROCESS_OFFLINE',
            'CLOSE_PLAYBACK',
            'END_SESSION',
            'RESET_KEY'
        )
    ),
    CONSTRAINT ck_stream_media_operations_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'CANCELLED')
    ),
    CONSTRAINT ck_stream_media_operations_generation CHECK (credential_generation >= 0),
    CONSTRAINT ck_stream_media_operations_revision CHECK (media_revision >= 0),
    CONSTRAINT ck_stream_media_operations_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_stream_media_operations_source_type CHECK (
        target_source_type IS NULL OR target_source_type IN ('rtmpConn', 'rtmpsConn')
    ),
    CONSTRAINT ck_stream_media_operations_lease CHECK (
        (lease_until IS NULL AND lease_owner IS NULL)
        OR (lease_until IS NOT NULL AND lease_owner IS NOT NULL)
    ),
    CONSTRAINT ck_stream_media_operations_payload_not_blank CHECK (
        payload IS NULL OR length(btrim(payload)) > 0
    )
);

CREATE UNIQUE INDEX uq_stream_media_operations_dedup
    ON stream_media_operations (channel_id, operation_type, deduplication_key)
    WHERE deduplication_key IS NOT NULL;

CREATE UNIQUE INDEX uq_stream_media_operations_event_dedup
    ON stream_media_operations (deduplication_key)
    WHERE operation_type IN ('PROCESS_ONLINE', 'PROCESS_OFFLINE')
      AND deduplication_key IS NOT NULL;

CREATE UNIQUE INDEX uq_stream_media_operations_one_candidate_session
    ON stream_media_operations (channel_id)
    WHERE candidate_session_id IS NOT NULL
      AND status IN ('PENDING', 'RUNNING');

CREATE UNIQUE INDEX uq_stream_media_operations_active_provision
    ON stream_media_operations (channel_id)
    WHERE operation_type = 'PROVISION_INGEST'
      AND status IN ('PENDING', 'RUNNING');

CREATE UNIQUE INDEX uq_stream_media_operations_active_reset
    ON stream_media_operations (channel_id)
    WHERE operation_type = 'RESET_KEY'
      AND status IN ('PENDING', 'RUNNING');

CREATE INDEX idx_stream_media_operations_due
    ON stream_media_operations (next_attempt_at ASC, created_at ASC, id ASC)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX idx_stream_media_operations_channel_created
    ON stream_media_operations (channel_id, created_at DESC, id DESC);

CREATE TABLE stream_media_runtime_state (
    id VARCHAR(50) NOT NULL,
    server_started_at VARCHAR(100),
    last_reconciled_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_stream_media_runtime_state PRIMARY KEY (id)
);
