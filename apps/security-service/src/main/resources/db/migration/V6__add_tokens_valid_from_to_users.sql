-- Per-user access-token validity floor. When set, any access token whose
-- `iat` claim precedes this timestamp is treated as revoked, even though
-- individual access-token jtis are not tracked server-side. Set by
-- "revoke all sessions for user" (TokenRevocationService.revokeAllForUser).
ALTER TABLE users ADD COLUMN tokens_valid_from TIMESTAMPTZ;
