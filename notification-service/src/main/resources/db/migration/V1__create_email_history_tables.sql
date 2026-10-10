CREATE TABLE email_notifications (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL,
    user_id UUID NOT NULL,
    email_type VARCHAR(50) NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    template_id VARCHAR(255),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_http_status SMALLINT,
    last_error TEXT,
    provider_message_id TEXT,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_email_notifications PRIMARY KEY (id),
    CONSTRAINT uk_email_notifications_event_id UNIQUE (event_id),
    CONSTRAINT ck_email_notifications_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'FAILED', 'EXPIRED', 'UNKNOWN')
    ),
    CONSTRAINT ck_email_notifications_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_email_notifications_user_created_at ON email_notifications (user_id, created_at);

CREATE TABLE email_delivery_events (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    notification_id UUID NOT NULL,
    sg_event_id TEXT,
    sg_message_id TEXT,
    event_type VARCHAR(50) NOT NULL,
    provider_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    response TEXT,
    reason TEXT,
    smtp_status VARCHAR(50),
    CONSTRAINT pk_email_delivery_events PRIMARY KEY (id),
    CONSTRAINT uk_email_delivery_events_sg_event_id UNIQUE (sg_event_id),
    CONSTRAINT fk_email_delivery_events_notification FOREIGN KEY (notification_id)
        REFERENCES email_notifications (id)
);

CREATE INDEX idx_email_delivery_events_notification_timestamp
    ON email_delivery_events (notification_id, provider_timestamp);
