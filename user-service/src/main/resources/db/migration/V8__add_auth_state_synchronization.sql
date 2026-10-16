ALTER TABLE users ADD COLUMN auth_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE auth_state_sync_tasks (
    user_id UUID NOT NULL PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_auth_state_sync_tasks_due ON auth_state_sync_tasks (next_attempt_at);
