INSERT INTO permissions (name)
SELECT p.name FROM (VALUES ('CHANNEL_LIVE_VIEW_BAN'), ('CHANNEL_FULL_BAN'), ('CHANNEL_LIVE_VIEW_UNBAN'), ('CHANNEL_FULL_UNBAN'), ('CHANNEL_BAN_VIEW')) AS p(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions existing WHERE existing.name = p.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id FROM roles role JOIN permissions permission ON permission.name IN
    ('CHANNEL_CHAT_BAN', 'CHANNEL_CHAT_UNBAN', 'CHANNEL_LIVE_VIEW_BAN', 'CHANNEL_FULL_BAN', 'CHANNEL_LIVE_VIEW_UNBAN', 'CHANNEL_FULL_UNBAN', 'CHANNEL_BAN_VIEW')
WHERE role.name = 'STREAMER' AND NOT EXISTS (
    SELECT 1 FROM role_permissions existing WHERE existing.role_id = role.id AND existing.permission_id = permission.id
);

INSERT INTO role_permissions (role_id, permission_id)
SELECT old_grant.role_id, new_permission.id FROM role_permissions old_grant
JOIN permissions old_permission ON old_permission.id = old_grant.permission_id AND old_permission.name = 'CHANNEL_CHAT_BAN_VIEW'
JOIN permissions new_permission ON new_permission.name = 'CHANNEL_BAN_VIEW'
WHERE NOT EXISTS (SELECT 1 FROM role_permissions existing WHERE existing.role_id = old_grant.role_id AND existing.permission_id = new_permission.id);

-- Existing sessions load authorities from Redis; queue committed permission changes for publication.
UPDATE users
SET auth_revision = auth_revision + 1
WHERE id IN (
    SELECT user_role.user_id
    FROM user_roles user_role
    JOIN roles role ON role.id = user_role.role_id
    WHERE role.name = 'STREAMER' OR EXISTS (SELECT 1 FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id WHERE rp.role_id = role.id AND p.name = 'CHANNEL_CHAT_BAN_VIEW')
);

UPDATE auth_state_sync_tasks task
SET revision = (SELECT account.auth_revision FROM users account WHERE account.id = task.user_id),
    attempts = 0,
    next_attempt_at = CURRENT_TIMESTAMP
WHERE user_id IN (
    SELECT user_role.user_id
    FROM user_roles user_role
    JOIN roles role ON role.id = user_role.role_id
    WHERE role.name = 'STREAMER' OR EXISTS (SELECT 1 FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id WHERE rp.role_id = role.id AND p.name = 'CHANNEL_CHAT_BAN_VIEW')
);

INSERT INTO auth_state_sync_tasks (user_id, revision)
SELECT DISTINCT account.id, account.auth_revision
FROM users account
JOIN user_roles user_role ON user_role.user_id = account.id
JOIN roles role ON role.id = user_role.role_id
WHERE (role.name = 'STREAMER' OR EXISTS (SELECT 1 FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id WHERE rp.role_id = role.id AND p.name = 'CHANNEL_CHAT_BAN_VIEW'))
  AND NOT EXISTS (
      SELECT 1 FROM auth_state_sync_tasks existing WHERE existing.user_id = account.id
  );
