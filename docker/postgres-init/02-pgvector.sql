-- =============================================================================
-- pgvector extension for Jarvis Memory Service
-- Run on jarvis_memory database
-- =============================================================================

\c jarvis_memory;

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Verify installation
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE NOTICE 'pgvector extension installed successfully';
    ELSE
        RAISE EXCEPTION 'pgvector extension failed to install';
    END IF;
END $$;

-- =============================================================================
-- conversation_message: Raw log of all messages
-- =============================================================================
CREATE TABLE IF NOT EXISTS conversation_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    metadata JSONB DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_conv_msg_user ON conversation_message(user_id);
CREATE INDEX IF NOT EXISTS idx_conv_msg_session ON conversation_message(session_id);
CREATE INDEX IF NOT EXISTS idx_conv_msg_created ON conversation_message(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_conv_msg_user_session ON conversation_message(user_id, session_id);

COMMENT ON TABLE conversation_message IS 'Raw log of all conversation messages for history and auditing';
COMMENT ON COLUMN conversation_message.metadata IS 'Additional metadata: intent, confidence, emotion, etc.';

-- =============================================================================
-- memory_chunk: Vectorized chunks for semantic search
-- =============================================================================
CREATE TABLE IF NOT EXISTS memory_chunk (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(255) NOT NULL,
    source_message_ids UUID[] NOT NULL,
    chunk_text TEXT NOT NULL,
    embedding vector(384),  -- multilingual-e5-small = 384 dimensions
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    metadata JSONB DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_chunk_user ON memory_chunk(user_id);
CREATE INDEX IF NOT EXISTS idx_chunk_created ON memory_chunk(created_at DESC);

-- IVFFlat index for approximate nearest neighbor search
-- NOTE: Run ANALYZE memory_chunk; after bulk inserts for optimal performance
-- lists=100 is good for up to ~100k vectors, increase for larger datasets
CREATE INDEX IF NOT EXISTS idx_chunk_embedding 
    ON memory_chunk USING ivfflat (embedding vector_cosine_ops) 
    WITH (lists = 100);

COMMENT ON TABLE memory_chunk IS 'Vectorized chunks of conversations for semantic search';
COMMENT ON COLUMN memory_chunk.embedding IS 'Vector embedding (384 dims) from multilingual-e5-small';
COMMENT ON COLUMN memory_chunk.source_message_ids IS 'Array of conversation_message IDs that form this chunk';
COMMENT ON COLUMN memory_chunk.metadata IS 'Metadata: topic, importance, tags, summary, etc.';

-- =============================================================================
-- session_summary: LLM-generated summaries per session
-- =============================================================================
CREATE TABLE IF NOT EXISTS session_summary (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(255) UNIQUE NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    summary_text TEXT NOT NULL,
    message_count INTEGER DEFAULT 0,
    key_topics TEXT[] DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_summary_user ON session_summary(user_id);
CREATE INDEX IF NOT EXISTS idx_summary_updated ON session_summary(updated_at DESC);

COMMENT ON TABLE session_summary IS 'LLM-generated summaries of conversation sessions';
COMMENT ON COLUMN session_summary.key_topics IS 'Extracted key topics from the session';

-- =============================================================================
-- Helper function: Update updated_at on session_summary
-- =============================================================================
CREATE OR REPLACE FUNCTION update_session_summary_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_update_session_summary ON session_summary;
CREATE TRIGGER trigger_update_session_summary
    BEFORE UPDATE ON session_summary
    FOR EACH ROW
    EXECUTE FUNCTION update_session_summary_timestamp();

-- =============================================================================
-- Grant permissions
-- =============================================================================
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO jarvis;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO jarvis;

-- =============================================================================
-- Verification
-- =============================================================================
DO $$
DECLARE
    table_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO table_count 
    FROM information_schema.tables 
    WHERE table_schema = 'public' 
    AND table_name IN ('conversation_message', 'memory_chunk', 'session_summary');
    
    IF table_count = 3 THEN
        RAISE NOTICE 'All memory tables created successfully (3/3)';
    ELSE
        RAISE EXCEPTION 'Expected 3 tables, found %', table_count;
    END IF;
END $$;



