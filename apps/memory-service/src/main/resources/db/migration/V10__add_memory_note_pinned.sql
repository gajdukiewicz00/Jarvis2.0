-- =========================================================================
-- Roadmap #11 — memory-management UI + voice backend: pinned notes.
--
-- pinned — owner/voice ("pin this note") flag on memory_notes. Pinned
--          notes are:
--            * excluded from MemoryExpiryCleanupService's TTL sweep
--              regardless of expires_at (see
--              MemoryNoteRepository.findByStatusAndExpiresAtBeforeAndPinnedFalse)
--            * ranked ahead of non-pinned notes in list/keyword/semantic
--              search (see MemoryNoteRepository.search /
--              searchByCategoryAndScope / searchByText and
--              MemoryNoteService.searchUnified)
-- =========================================================================

ALTER TABLE memory_notes ADD COLUMN IF NOT EXISTS pinned BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_memory_notes_pinned ON memory_notes (pinned);
