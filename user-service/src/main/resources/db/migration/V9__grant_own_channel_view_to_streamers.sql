INSERT INTO permissions (name)
SELECT 'CHANNEL_VIEW_OWN'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'CHANNEL_VIEW_OWN');

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
JOIN permissions permission ON permission.name = 'CHANNEL_VIEW_OWN'
WHERE role.name = 'STREAMER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions existing
      WHERE existing.role_id = role.id AND existing.permission_id = permission.id
  );
