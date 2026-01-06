-- Create conversation_message table for raw message log
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

COMMENT ON TABLE conversation_message IS 'Raw log of all conversation messages';



