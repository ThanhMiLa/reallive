-- Find affected accounts while the grants still exist. Publish their new
-- authorities through the existing sync worker after the migration commits.
UPDATE users
SET auth_revision = auth_revision + 1
WHERE id IN (
    SELECT user_role.user_id
    FROM user_roles user_role
    JOIN role_permissions grant_entry ON grant_entry.role_id = user_role.role_id
    JOIN permissions permission ON permission.id = grant_entry.permission_id
    WHERE permission.name IN (
        'CHANNEL_CHAT_BAN', 'CHANNEL_CHAT_UNBAN', 'CHANNEL_LIVE_VIEW_BAN', 'CHANNEL_LIVE_VIEW_UNBAN',
        'CHANNEL_FULL_BAN', 'CHANNEL_FULL_UNBAN', 'CHANNEL_BAN_VIEW', 'CHANNEL_CHAT_BAN_VIEW'
    )
);

UPDATE auth_state_sync_tasks task
SET revision = (SELECT account.auth_revision FROM users account WHERE account.id = task.user_id),
    attempts = 0,
    next_attempt_at = CURRENT_TIMESTAMP
WHERE task.user_id IN (
    SELECT user_role.user_id
    FROM user_roles user_role
    JOIN role_permissions grant_entry ON grant_entry.role_id = user_role.role_id
    JOIN permissions permission ON permission.id = grant_entry.permission_id
    WHERE permission.name IN (
        'CHANNEL_CHAT_BAN', 'CHANNEL_CHAT_UNBAN', 'CHANNEL_LIVE_VIEW_BAN', 'CHANNEL_LIVE_VIEW_UNBAN',
        'CHANNEL_FULL_BAN', 'CHANNEL_FULL_UNBAN', 'CHANNEL_BAN_VIEW', 'CHANNEL_CHAT_BAN_VIEW'
    )
);

INSERT INTO auth_state_sync_tasks (user_id, revision)
SELECT DISTINCT account.id, account.auth_revision
FROM users account
JOIN user_roles user_role ON user_role.user_id = account.id
JOIN role_permissions grant_entry ON grant_entry.role_id = user_role.role_id
JOIN permissions permission ON permission.id = grant_entry.permission_id
WHERE permission.name IN (
    'CHANNEL_CHAT_BAN', 'CHANNEL_CHAT_UNBAN', 'CHANNEL_LIVE_VIEW_BAN', 'CHANNEL_LIVE_VIEW_UNBAN',
    'CHANNEL_FULL_BAN', 'CHANNEL_FULL_UNBAN', 'CHANNEL_BAN_VIEW', 'CHANNEL_CHAT_BAN_VIEW'
)
AND NOT EXISTS (
    SELECT 1 FROM auth_state_sync_tasks task WHERE task.user_id = account.id
);

DELETE FROM role_permissions
WHERE permission_id IN (
    SELECT id
    FROM permissions
    WHERE name IN (
        'CHANNEL_CHAT_BAN', 'CHANNEL_CHAT_UNBAN', 'CHANNEL_LIVE_VIEW_BAN', 'CHANNEL_LIVE_VIEW_UNBAN',
        'CHANNEL_FULL_BAN', 'CHANNEL_FULL_UNBAN', 'CHANNEL_BAN_VIEW', 'CHANNEL_CHAT_BAN_VIEW'
    )
);

DELETE FROM permissions
WHERE name IN (
    'CHANNEL_CHAT_BAN', 'CHANNEL_CHAT_UNBAN', 'CHANNEL_LIVE_VIEW_BAN', 'CHANNEL_LIVE_VIEW_UNBAN',
    'CHANNEL_FULL_BAN', 'CHANNEL_FULL_UNBAN', 'CHANNEL_BAN_VIEW', 'CHANNEL_CHAT_BAN_VIEW'
);
