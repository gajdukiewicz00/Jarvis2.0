-- Phase: CV screen-context memory consumer.
-- Persists wide-view screen-context observations produced by
-- vision-security-service on the jarvis.cv.screen_context.created topic.
--
-- Privacy notes:
--  * screenshot_bytes is OPTIONAL raw image storage, gated by
--    jarvis.memory.cv.store-raw-screenshot. In a clustered deployment the
--    consumer pod cannot read the producer host's screenshot file, so bytes
--    stay NULL there and only the path reference is kept (see docs).
--  * idempotency_key dedupes redelivered events (no double-persist).

CREATE TABLE IF NOT EXISTS screen_context_observation (
    id                  UUID PRIMARY KEY,
    idempotency_key     TEXT NOT NULL UNIQUE,
    user_id             TEXT,
    captured_at         TIMESTAMPTZ,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    duration_ms         BIGINT,
    display_server      TEXT,
    active_window_title TEXT,
    active_process_name TEXT,
    semantic_tags       JSONB NOT NULL DEFAULT '[]',
    ocr_text            TEXT,
    ocr_blocks          JSONB NOT NULL DEFAULT '[]',
    ui_elements         JSONB NOT NULL DEFAULT '[]',
    objects             JSONB NOT NULL DEFAULT '[]',
    screenshot_path     TEXT,
    screenshot_bytes    BYTEA,
    engine              TEXT,
    ocr_language        TEXT,
    embedding           vector(384),       -- multilingual-e5-small; NULL when embedding-service is off
    embedding_model     TEXT,
    success             BOOLEAN NOT NULL DEFAULT true,
    error               TEXT
);

CREATE INDEX IF NOT EXISTS idx_screen_ctx_user
    ON screen_context_observation (user_id);
CREATE INDEX IF NOT EXISTS idx_screen_ctx_captured
    ON screen_context_observation (captured_at DESC);
-- Vector ANN index for future semantic recall of screen history.
CREATE INDEX IF NOT EXISTS idx_screen_ctx_embedding
    ON screen_context_observation USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

COMMENT ON TABLE  screen_context_observation IS 'CV screen-context observations consumed from jarvis.cv.screen_context.created';
COMMENT ON COLUMN screen_context_observation.screenshot_bytes IS 'Optional raw screenshot; NULL unless jarvis.memory.cv.store-raw-screenshot and the file is readable by this process';
COMMENT ON COLUMN screen_context_observation.embedding IS 'OCR-text embedding (384 dims); NULL when embedding disabled/unavailable';
