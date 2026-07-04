-- =========================================================================
-- Phase 8 — audit_events table.
-- Persistent projection of jarvis.audit.events Kafka topic.
-- One row per privileged action across orchestrator / desktop-agent /
-- voice-gateway / api-gateway. Indexed for "show me recent activity"
-- queries from the desktop panel.
-- =========================================================================

CREATE TABLE IF NOT EXISTS audit_events (
    event_id          TEXT PRIMARY KEY,
    event_type        TEXT NOT NULL,
    category          TEXT NOT NULL,
    severity          TEXT NOT NULL,
    source            TEXT NOT NULL,
    trace_id          TEXT,
    agent_id          TEXT,
    user_id           TEXT,
    session_id        TEXT,
    command_id        TEXT,
    occurred_at       TIMESTAMPTZ NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    payload           JSONB
);

CREATE INDEX IF NOT EXISTS idx_audit_events_occurred_at
    ON audit_events (occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_events_event_type
    ON audit_events (event_type);

CREATE INDEX IF NOT EXISTS idx_audit_events_agent_id
    ON audit_events (agent_id);

CREATE INDEX IF NOT EXISTS idx_audit_events_user_id
    ON audit_events (user_id);

CREATE INDEX IF NOT EXISTS idx_audit_events_command_id
    ON audit_events (command_id);

CREATE INDEX IF NOT EXISTS idx_audit_events_severity
    ON audit_events (severity);
