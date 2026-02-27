-- Hard fail if legacy default admin credentials still exist.
-- This blocks startup in insecure state while keeping older migrations immutable.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM users
        WHERE username = 'admin'
          AND password = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SECURITY_HARD_FAIL: legacy default admin credential detected (username=admin, known V1 hash). Remove it before startup.';
    END IF;
END
$$;
