-- User Service Data Model: sections 1.1.1 through 1.1.8.
-- UUID identifiers are supplied by the application.
-- updated_at defaults apply on insert; the application maintains it on update.

-- 1.1.1 users
CREATE TABLE users (
    id UUID NOT NULL,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'BANNED'))
);

CREATE INDEX idx_users_status ON users (status);

-- 1.1.2 user_google_accounts
CREATE TABLE user_google_accounts (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    google_account_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_google_accounts PRIMARY KEY (id),
    CONSTRAINT uk_user_google_accounts_user_id UNIQUE (user_id),
    CONSTRAINT uk_user_google_accounts_google_account_id UNIQUE (google_account_id),
    CONSTRAINT fk_user_google_accounts_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- 1.1.3 roles
CREATE TABLE roles (
    id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uk_roles_name UNIQUE (name)
);

-- 1.1.4 user_roles
CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
);

-- The composite primary key already indexes user_id as its leading column.
CREATE INDEX idx_user_roles_role_id ON user_roles (role_id);

-- 1.1.5 permissions
CREATE TABLE permissions (
    id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_permissions PRIMARY KEY (id),
    CONSTRAINT uk_permissions_name UNIQUE (name)
);

-- 1.1.6 role_permissions
CREATE TABLE role_permissions (
    role_id UUID NOT NULL,
    permission_id UUID NOT NULL,
    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions (id)
);

-- The composite primary key already indexes role_id as its leading column.
CREATE INDEX idx_role_permissions_permission_id ON role_permissions (permission_id);

-- 1.1.7 invalidated_tokens
CREATE TABLE invalidated_tokens (
    jti UUID NOT NULL,
    expiry_time TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_invalidated_tokens PRIMARY KEY (jti)
);

CREATE INDEX idx_invalidated_tokens_expiry_time ON invalidated_tokens (expiry_time);

-- 1.1.8 profiles
CREATE TABLE profiles (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    avatar_url VARCHAR(500),
    dob DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_profiles PRIMARY KEY (id),
    CONSTRAINT uk_profiles_user_id UNIQUE (user_id),
    CONSTRAINT fk_profiles_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- UNIQUE constraints already index user_google_accounts.user_id and profiles.user_id.
CREATE INDEX idx_profiles_display_name ON profiles (display_name);
