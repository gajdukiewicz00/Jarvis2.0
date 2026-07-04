-- =========================================================================
-- Phase 9+ — memory_search_audit table.
--
-- Per-retrieval audit trail for /memory/search calls. Complements the
-- existing audit_events table (V4), which is the projection of the
-- jarvis.audit.events Kafka topic and only carries privileged WRITE
-- actions. memory_search_audit captures READ-time retrievals so that
-- "which notes did Jarvis look at when it answered X?" is answerable
-- after the fact.
--
-- Privacy posture:
--   - query_hash is required (SHA-256 of the raw query, hex). Always
--     populated; safe to keep forever.
--   - query_excerpt is optional. Only populated when
--     logging.pii.allowQuerySnippet=true (see application.yml).
--   - retrieved_note_paths / retrieved_chunk_ids are JSONB arrays of
--     stable ids. Note bodies are NOT stored here; they live in
--     memory_notes.
-- =========================================================================

CREATE TABLE IF NOT EXISTS memory_search_audit (
    id                     UUID PRIMARY KEY,
    user_id                TEXT,
    query_hash             TEXT NOT NULL,
    query_excerpt          TEXT,
    selected_model         TEXT,
    retrieval_mode         TEXT NOT NULL,
    rerank_used            BOOLEAN NOT NULL DEFAULT FALSE,
    top_k                  INT NOT NULL,
    result_count           INT NOT NULL DEFAULT 0,
    retrieved_note_paths   JSONB NOT NULL DEFAULT '[]',
    retrieved_chunk_ids    JSONB NOT NULL DEFAULT '[]',
    processing_time_ms     INT,
    correlation_id         TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_memory_search_audit_created_at
    ON memory_search_audit (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_memory_search_audit_user_id
    ON memory_search_audit (user_id);

CREATE INDEX IF NOT EXISTS idx_memory_search_audit_query_hash
    ON memory_search_audit (query_hash);

CREATE INDEX IF NOT EXISTS idx_memory_search_audit_correlation_id
    ON memory_search_audit (correlation_id);
