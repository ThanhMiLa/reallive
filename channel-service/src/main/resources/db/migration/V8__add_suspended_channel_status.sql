ALTER TABLE channels DROP CONSTRAINT ck_channels_status;

ALTER TABLE channels
    ADD CONSTRAINT ck_channels_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'BANNED'));
