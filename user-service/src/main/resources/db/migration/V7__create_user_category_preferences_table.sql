CREATE TABLE IF NOT EXISTS user_category_preferences (
    user_id UUID NOT NULL,
    category_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_category_preferences PRIMARY KEY (user_id, category_id),
    CONSTRAINT fk_user_category_preferences_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_category_preferences_category_id ON user_category_preferences (category_id);
