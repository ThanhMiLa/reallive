ALTER TABLE stream_sessions
    ADD COLUMN thumbnail_url VARCHAR(1000),
    ADD COLUMN thumbnail_object_key VARCHAR(500),
    ADD COLUMN thumbnail_updated_at TIMESTAMP,
    ADD COLUMN thumbnail_job_id UUID,
    ADD COLUMN thumbnail_job_status VARCHAR(30),
    ADD COLUMN thumbnail_job_deadline_at TIMESTAMP;

ALTER TABLE stream_sessions
    ADD CONSTRAINT ck_stream_sessions_thumbnail_job_status CHECK (
        thumbnail_job_status IS NULL
        OR thumbnail_job_status IN ('PENDING_DISPATCH', 'ACCEPTED', 'SUCCEEDED', 'FAILED')
    ),
    ADD CONSTRAINT ck_stream_sessions_thumbnail_job_fields CHECK (
        (thumbnail_job_status IS NULL AND thumbnail_job_id IS NULL AND thumbnail_job_deadline_at IS NULL)
        OR (thumbnail_job_status IS NOT NULL AND thumbnail_job_id IS NOT NULL AND thumbnail_job_deadline_at IS NOT NULL)
    );

CREATE INDEX idx_stream_sessions_thumbnail_due
    ON stream_sessions (thumbnail_updated_at ASC NULLS FIRST, id ASC)
    WHERE status = 'LIVE'
      AND (thumbnail_job_status IS NULL OR thumbnail_job_status NOT IN ('PENDING_DISPATCH', 'ACCEPTED'));

CREATE INDEX idx_stream_sessions_thumbnail_pending_dispatch
    ON stream_sessions (thumbnail_job_deadline_at ASC, id ASC)
    WHERE thumbnail_job_status = 'PENDING_DISPATCH';

CREATE INDEX idx_stream_sessions_thumbnail_job_deadline
    ON stream_sessions (thumbnail_job_deadline_at ASC, id ASC)
    WHERE thumbnail_job_status IN ('PENDING_DISPATCH', 'ACCEPTED');
