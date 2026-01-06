-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Create memory_chunk table for vectorized chunks
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
CREATE INDEX IF NOT EXISTS idx_chunk_embedding 
    ON memory_chunk USING ivfflat (embedding vector_cosine_ops) 
    WITH (lists = 100);

COMMENT ON TABLE memory_chunk IS 'Vectorized chunks for semantic search';
COMMENT ON COLUMN memory_chunk.embedding IS 'Vector embedding (384 dims) from multilingual-e5-small';



