-- Per-jti token revocation store. Consulted on every JWT validation
-- (JwtService.parseClaims) so an individually revoked access or refresh
-- token is rejected even before its natural expiry.
CREATE TABLE revoked_tokens (
    jti UUID PRIMARY KEY,
    token_type VARCHAR(20) NOT NULL,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    revoked_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoke_reason VARCHAR(64),
    revoked_by BIGINT REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_revoked_tokens_expires_at ON revoked_tokens(expires_at);
CREATE INDEX idx_revoked_tokens_user_id ON revoked_tokens(user_id);
