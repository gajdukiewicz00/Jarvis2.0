-- B2: per-chunk privacy level for memory privacy enforcement.
-- Default 'private' = usable by local AND external providers; mark 'local_only'
-- or 'sensitive' to restrict to local providers. Backward-compatible: existing
-- chunks become 'private' (current behaviour preserved for the local llama brain).
ALTER TABLE memory_chunk ADD COLUMN IF NOT EXISTS privacy VARCHAR(32) NOT NULL DEFAULT 'private';
