ALTER TABLE email_delivery_events ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE email_delivery_events ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;

UPDATE email_delivery_events SET created_at = received_at, updated_at = received_at;

ALTER TABLE email_delivery_events ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE email_delivery_events ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE email_delivery_events ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE email_delivery_events ALTER COLUMN updated_at SET NOT NULL;
