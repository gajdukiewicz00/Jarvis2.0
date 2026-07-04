-- Phase 12 — durable activity timeline for the life-map.
-- Replaces the volatile in-memory ring buffer so history survives restarts.
CREATE TABLE IF NOT EXISTS life_activity_entries (
    id               BIGSERIAL PRIMARY KEY,
    entry_id         VARCHAR(64)  NOT NULL UNIQUE,
    user_id          VARCHAR(255) NOT NULL,
    started_at       TIMESTAMP    NOT NULL,
    ended_at         TIMESTAMP,
    duration_seconds BIGINT       NOT NULL DEFAULT 0,
    category         VARCHAR(30),
    app_name         VARCHAR(255),
    window_title     VARCHAR(500),
    source           VARCHAR(50)
);

CREATE INDEX IF NOT EXISTS idx_life_activity_user_started
    ON life_activity_entries (user_id, started_at);
