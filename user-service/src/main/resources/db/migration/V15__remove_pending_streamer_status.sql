-- Preserve the ineligible state of legacy pending streamers without granting streaming access.
UPDATE streamers
SET status = 'SUSPENDED'
WHERE status = 'PENDING';

ALTER TABLE streamers DROP CONSTRAINT ck_streamers_status;

ALTER TABLE streamers
    ADD CONSTRAINT ck_streamers_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'BANNED'));
