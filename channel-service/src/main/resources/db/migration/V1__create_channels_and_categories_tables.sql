CREATE TABLE channels (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    streamer_id UUID NOT NULL,
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(150) NOT NULL,
    description VARCHAR(1000),
    avatar_url VARCHAR(500),
    banner_url VARCHAR(500),
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT pk_channels PRIMARY KEY (id),
    CONSTRAINT uk_channels_streamer_id UNIQUE (streamer_id),
    CONSTRAINT uk_channels_slug UNIQUE (slug),
    CONSTRAINT ck_channels_status CHECK (status IN ('ACTIVE', 'BANNED'))
);

CREATE INDEX idx_channels_name ON channels (name);
CREATE INDEX idx_channels_status ON channels (status);
CREATE INDEX idx_channels_deleted_at ON channels (deleted_at);

CREATE TABLE categories (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    thumbnail_url VARCHAR(500),
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uk_categories_name UNIQUE (name),
    CONSTRAINT uk_categories_slug UNIQUE (slug),
    CONSTRAINT ck_categories_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_categories_status ON categories (status);

CREATE TABLE channel_categories (
    channel_id UUID NOT NULL,
    category_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_channel_categories PRIMARY KEY (channel_id, category_id),
    CONSTRAINT fk_channel_categories_channel FOREIGN KEY (channel_id) REFERENCES channels (id),
    CONSTRAINT fk_channel_categories_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

CREATE INDEX idx_channel_categories_category_id ON channel_categories (category_id);
