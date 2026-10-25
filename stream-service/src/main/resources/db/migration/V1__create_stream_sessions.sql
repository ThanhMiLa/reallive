CREATE TABLE stream_sessions (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    channel_id UUID NOT NULL,
    streamer_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    category_id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    visibility VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    playback_path VARCHAR(500) NOT NULL,
    creation_request_id UUID NOT NULL,
    media_path_state VARCHAR(30) NOT NULL,
    media_source_id UUID,
    media_source_type VARCHAR(30),
    disconnected_at TIMESTAMP,
    reconnect_deadline_at TIMESTAMP,
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_stream_sessions PRIMARY KEY (id),
    CONSTRAINT uk_stream_sessions_playback_path UNIQUE (playback_path),
    CONSTRAINT uk_stream_sessions_owner_creation_request UNIQUE (owner_user_id, creation_request_id),
    CONSTRAINT ck_stream_sessions_title_not_blank CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_stream_sessions_playback_path_not_blank CHECK (length(btrim(playback_path)) > 0),
    CONSTRAINT ck_stream_sessions_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE', 'UNLISTED')),
    CONSTRAINT ck_stream_sessions_status CHECK (status IN ('READY', 'LIVE', 'RECONNECTING', 'ENDED')),
    CONSTRAINT ck_stream_sessions_media_path_state CHECK (
        media_path_state IN ('PENDING_OPEN', 'OPEN', 'PENDING_CLOSE', 'CLOSED')
    ),
    CONSTRAINT ck_stream_sessions_media_source_type CHECK (
        media_source_type IS NULL OR media_source_type IN ('rtmpConn', 'rtmpsConn')
    ),
    CONSTRAINT ck_stream_sessions_reconnecting_deadline CHECK (
        status <> 'RECONNECTING' OR (disconnected_at IS NOT NULL AND reconnect_deadline_at IS NOT NULL)
    ),
    CONSTRAINT ck_stream_sessions_live_state CHECK (
        status <> 'LIVE' OR (
            started_at IS NOT NULL
            AND media_source_id IS NOT NULL
            AND media_source_type IS NOT NULL
            AND reconnect_deadline_at IS NULL
        )
    ),
    CONSTRAINT ck_stream_sessions_ended_at CHECK (
        (status = 'ENDED' AND ended_at IS NOT NULL) OR (status <> 'ENDED' AND ended_at IS NULL)
    ),
    CONSTRAINT ck_stream_sessions_media_state_alignment CHECK (
        (status IN ('READY', 'LIVE', 'RECONNECTING') AND media_path_state IN ('PENDING_OPEN', 'OPEN'))
        OR (status = 'ENDED' AND media_path_state IN ('PENDING_CLOSE', 'CLOSED'))
    )
);

CREATE UNIQUE INDEX uq_stream_sessions_channel_slot
    ON stream_sessions (channel_id)
    WHERE status IN ('READY', 'LIVE', 'RECONNECTING')
       OR media_path_state <> 'CLOSED';

CREATE INDEX idx_stream_sessions_live_discovery
    ON stream_sessions (visibility, status, started_at DESC, id ASC);

CREATE INDEX idx_stream_sessions_owner_current
    ON stream_sessions (owner_user_id, created_at DESC, id DESC);

CREATE INDEX idx_stream_sessions_reconnect_deadline
    ON stream_sessions (status, reconnect_deadline_at)
    WHERE status = 'RECONNECTING';

CREATE INDEX idx_stream_sessions_channel_id ON stream_sessions (channel_id);
CREATE INDEX idx_stream_sessions_streamer_id ON stream_sessions (streamer_id);
CREATE INDEX idx_stream_sessions_category_id ON stream_sessions (category_id);

CREATE TABLE stream_status_history (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    stream_id UUID NOT NULL,
    previous_status VARCHAR(30),
    status VARCHAR(30) NOT NULL,
    reason VARCHAR(500),
    media_source_id UUID,
    media_event_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_stream_status_history PRIMARY KEY (id),
    CONSTRAINT fk_stream_status_history_stream FOREIGN KEY (stream_id)
        REFERENCES stream_sessions (id) ON DELETE CASCADE,
    CONSTRAINT ck_stream_status_history_previous_status CHECK (
        previous_status IS NULL OR previous_status IN ('READY', 'LIVE', 'RECONNECTING', 'ENDED')
    ),
    CONSTRAINT ck_stream_status_history_status CHECK (
        status IN ('READY', 'LIVE', 'RECONNECTING', 'ENDED')
    )
);

CREATE INDEX idx_stream_status_history_stream_created_at
    ON stream_status_history (stream_id, created_at ASC, id ASC);

CREATE UNIQUE INDEX uq_stream_status_history_media_event_id
    ON stream_status_history (media_event_id)
    WHERE media_event_id IS NOT NULL;

CREATE TABLE stream_media_sync_tasks (
    stream_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_until TIMESTAMP,
    lease_owner VARCHAR(100),
    last_error VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_stream_media_sync_tasks PRIMARY KEY (stream_id),
    CONSTRAINT fk_stream_media_sync_tasks_stream FOREIGN KEY (stream_id)
        REFERENCES stream_sessions (id) ON DELETE CASCADE,
    CONSTRAINT ck_stream_media_sync_tasks_revision CHECK (revision > 0),
    CONSTRAINT ck_stream_media_sync_tasks_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_stream_media_sync_tasks_lease CHECK (
        (lease_until IS NULL AND lease_owner IS NULL)
        OR (lease_until IS NOT NULL AND lease_owner IS NOT NULL)
    )
);

CREATE INDEX idx_stream_media_sync_tasks_due
    ON stream_media_sync_tasks (next_attempt_at, lease_until);
