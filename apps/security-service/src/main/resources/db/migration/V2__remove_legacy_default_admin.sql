-- Remove legacy hardcoded admin account created by V1 migration.
-- Keep V1 immutable to avoid checksum drift on existing environments.
DELETE FROM users
WHERE username = 'admin'
  AND password = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'
  AND role = 'ADMIN';
