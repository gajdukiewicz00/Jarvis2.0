-- Tracks when the overall session (rotation chain) started, independent of
-- any single refresh token's own issued_at. Carried over unchanged across
-- rotations so AuthService.refresh can enforce an absolute session TTL
-- (jarvis.jwt.absolute-session-ttl) that a rolling refresh window would
-- otherwise never hit.
ALTER TABLE refresh_tokens ADD COLUMN session_started_at TIMESTAMPTZ;

UPDATE refresh_tokens
SET session_started_at = issued_at
WHERE session_started_at IS NULL;

ALTER TABLE refresh_tokens ALTER COLUMN session_started_at SET NOT NULL;
