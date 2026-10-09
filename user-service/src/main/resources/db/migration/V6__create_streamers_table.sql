-- User Service Data Model: section 1.1.9.
CREATE TABLE streamers (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    verified_at TIMESTAMP,
    suspended_at TIMESTAMP,
    suspended_until TIMESTAMP,
    suspension_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_streamers PRIMARY KEY (id),
    CONSTRAINT uk_streamers_user_id UNIQUE (user_id),
    CONSTRAINT fk_streamers_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_streamers_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'BANNED'))
);

CREATE INDEX idx_streamers_status ON streamers (status);
CREATE INDEX idx_streamers_suspended_until ON streamers (suspended_until);
CREATE INDEX idx_streamers_status_suspended_until ON streamers (status, suspended_until);
