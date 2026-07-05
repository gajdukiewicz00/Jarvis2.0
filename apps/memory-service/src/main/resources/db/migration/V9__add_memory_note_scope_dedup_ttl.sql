-- =========================================================================
-- Roadmap P1 #9 — user-managed memory: typed scopes, content-hash dedup,
-- and TTL/expiry for memory_notes.
--
-- scope         — coarse ownership/lifecycle classification, orthogonal to
--                 the existing Obsidian-vault-layout `category` column.
--                 One of USER_PROFILE / PROJECT / SESSION / FINANCE /
--                 HEALTH / TEMPORARY (see MemoryScope). Defaults to
--                 USER_PROFILE for pre-existing rows. Filterable via
--                 GET /api/v1/memory/notes?scope=.
-- content_hash  — SHA-256 of normalized title+body, computed on ingest by
--                 MemoryNoteService so near-duplicate writes can be
--                 rejected or merged instead of piling up copies.
-- expires_at    — optional TTL. MemoryExpiryCleanupService periodically
--                 forgets (soft-deletes, same path as the "forget this"
--                 flow) ACTIVE notes whose TTL has passed.
-- =========================================================================

ALTER TABLE memory_notes ADD COLUMN IF NOT EXISTS scope TEXT NOT NULL DEFAULT 'USER_PROFILE';
ALTER TABLE memory_notes ADD COLUMN IF NOT EXISTS content_hash TEXT;
ALTER TABLE memory_notes ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_memory_notes_scope        ON memory_notes (scope);
CREATE INDEX IF NOT EXISTS idx_memory_notes_content_hash ON memory_notes (content_hash);
CREATE INDEX IF NOT EXISTS idx_memory_notes_expires_at   ON memory_notes (expires_at) WHERE expires_at IS NOT NULL;
