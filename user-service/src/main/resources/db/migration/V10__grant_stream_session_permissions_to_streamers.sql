INSERT INTO permissions (name)
SELECT stream_permission.name
FROM (VALUES
    ('STREAM_CREATE'),
    ('STREAM_VIEW_OWN'),
    ('STREAM_UPDATE'),
    ('STREAM_PUBLISH'),
    ('STREAM_END')
) AS stream_permission(name)
WHERE NOT EXISTS (
    SELECT 1 FROM permissions existing WHERE existing.name = stream_permission.name
);

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
JOIN permissions permission ON permission.name IN (
    'STREAM_CREATE', 'STREAM_VIEW_OWN', 'STREAM_UPDATE', 'STREAM_PUBLISH', 'STREAM_END'
)
WHERE role.name = 'STREAMER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions existing
      WHERE existing.role_id = role.id AND existing.permission_id = permission.id
  );

-- Existing sessions load authorities from Redis; queue committed permission changes for publication.
UPDATE users
SET auth_revision = auth_revision + 1
WHERE id IN (
    SELECT user_role.user_id
    FROM user_roles user_role
    JOIN roles role ON role.id = user_role.role_id
    WHERE role.name = 'STREAMER'
);

UPDATE auth_state_sync_tasks task
SET revision = (SELECT account.auth_revision FROM users account WHERE account.id = task.user_id),
    attempts = 0,
    next_attempt_at = CURRENT_TIMESTAMP
WHERE user_id IN (
    SELECT user_role.user_id
    FROM user_roles user_role
    JOIN roles role ON role.id = user_role.role_id
    WHERE role.name = 'STREAMER'
);

INSERT INTO auth_state_sync_tasks (user_id, revision)
SELECT account.id, account.auth_revision
FROM users account
JOIN user_roles user_role ON user_role.user_id = account.id
JOIN roles role ON role.id = user_role.role_id
WHERE role.name = 'STREAMER'
  AND NOT EXISTS (
      SELECT 1 FROM auth_state_sync_tasks existing WHERE existing.user_id = account.id
  );
