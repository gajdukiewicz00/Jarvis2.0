-- =========================================================================
-- Phase 9 — memory_notes table.
-- Source-of-truth for human-readable memory entries that are mirrored to
-- Obsidian Markdown files and indexed in pgvector.
--
-- Three-layer write flow per SPEC-1 § "Obsidian Memory Model":
--   PostgreSQL (this row) -> Obsidian (vault_relative_path .md) -> pgvector (embedding)
--
-- Forget flow soft-deletes the row (status=DELETED, body cleared,
-- deleted_at set), tombstones the Markdown file, and clears the vector;
-- a separate audit_events row carries the deletion record WITHOUT any
-- sensitive content.
-- =========================================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS memory_notes (
    memory_id             TEXT PRIMARY KEY,
    category              TEXT NOT NULL,
    title                 TEXT NOT NULL,
    summary               TEXT,
    body                  TEXT,
    vault_relative_path   TEXT,
    frontmatter           JSONB NOT NULL,
    embedding             vector(384),
    privacy               TEXT NOT NULL DEFAULT 'local-only',
    status                TEXT NOT NULL DEFAULT 'ACTIVE',
    confidence            NUMERIC(4,3),
    tags                  JSONB NOT NULL DEFAULT '[]',
    linked_entities       JSONB NOT NULL DEFAULT '[]',
    source                TEXT NOT NULL DEFAULT 'jarvis',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_memory_notes_category    ON memory_notes (category);
CREATE INDEX IF NOT EXISTS idx_memory_notes_status      ON memory_notes (status);
CREATE INDEX IF NOT EXISTS idx_memory_notes_created_at  ON memory_notes (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_memory_notes_privacy     ON memory_notes (privacy);
CREATE INDEX IF NOT EXISTS idx_memory_notes_tags_gin    ON memory_notes USING GIN (tags jsonb_path_ops);

-- Vector similarity index — ivfflat with cosine distance (matches existing
-- memory_chunk index conventions in V2). Lists chosen for ~10K notes.
CREATE INDEX IF NOT EXISTS idx_memory_notes_embedding
    ON memory_notes USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
