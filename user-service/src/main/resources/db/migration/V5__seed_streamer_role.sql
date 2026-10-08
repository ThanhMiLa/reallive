-- Preserve the existing role and its permissions when STREAMER has already been configured.
INSERT INTO roles (name)
SELECT 'STREAMER'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'STREAMER');
