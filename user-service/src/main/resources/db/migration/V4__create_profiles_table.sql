CREATE TABLE IF NOT EXISTS profiles (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    display_name VARCHAR(100),
    avatar_url VARCHAR(500),
    dob DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_profiles PRIMARY KEY (id),
    CONSTRAINT uk_profiles_user_id UNIQUE (user_id),
    CONSTRAINT fk_profiles_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- V1 already creates this table with a required display_name.
ALTER TABLE profiles ALTER COLUMN display_name DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_profiles_display_name ON profiles (display_name);
