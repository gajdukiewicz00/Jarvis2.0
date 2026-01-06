-- Create session_summary table for LLM-generated summaries
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

-- Trigger to auto-update updated_at
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

COMMENT ON TABLE session_summary IS 'LLM-generated session summaries';



