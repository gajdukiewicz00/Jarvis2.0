-- Persisted, filterable security audit-event store. Complements the
-- existing revocation bookkeeping (refresh_tokens / revoked_tokens, see
-- AuditService's original merge-based view) with a single table covering
-- the broader set of security-relevant events (login success/failure,
-- registration, password change, session revocation, ...) that were
-- previously only surfaced as Micrometer counters
-- (SecurityMetrics.auditEvent), not as queryable rows. Written by
-- AuditService.recordEvent, called alongside the existing
-- securityMetrics.auditEvent(...) hook at each security-relevant action.
CREATE TABLE audit_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(128)
);

CREATE INDEX idx_audit_events_user_id ON audit_events(user_id);
CREATE INDEX idx_audit_events_event_type ON audit_events(event_type);
CREATE INDEX idx_audit_events_occurred_at ON audit_events(occurred_at);
