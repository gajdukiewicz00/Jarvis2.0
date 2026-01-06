-- Add personality and memory tables to user-profile

-- User preferences table
CREATE TABLE IF NOT EXISTS user_preferences (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL UNIQUE,
    
    -- Basic info
    full_name VARCHAR(255),
    timezone VARCHAR(50) DEFAULT 'Europe/Warsaw',
    language VARCHAR(10) DEFAULT 'ru',
    occupation VARCHAR(255),
    
    -- Communication style
    communication_style VARCHAR(50) DEFAULT 'FRIENDLY',
    allow_auto_adaptation BOOLEAN DEFAULT true,
    allow_sarcasm BOOLEAN DEFAULT false,
    
    -- TTS preferences
    tts_voice_id VARCHAR(100) DEFAULT 'jarvis_male_en',
    tts_emotion_default VARCHAR(50) DEFAULT 'NEUTRAL',
    
    -- Favorites
    favorite_music_service VARCHAR(100),
    favorite_browser VARCHAR(100),
    favorite_ide VARCHAR(100),
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- User context patterns (work/sleep schedules)
CREATE TABLE IF NOT EXISTS user_context_patterns (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    
    pattern_type VARCHAR(50), -- WORK, SLEEP, FOCUS, EXERCISE
    
    -- Time patterns (JSON: {"monday": ["09:00-17:00"], ...})
    weekly_schedule JSONB,
    
    -- Additional context
    notes TEXT,
    
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_user_patterns FOREIGN KEY (user_id) 
        REFERENCES user_preferences(user_id) ON DELETE CASCADE
);

-- Dialogue summaries (compressed memory)
CREATE TABLE IF NOT EXISTS dialogue_summaries (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255),
    
    period_start TIMESTAMP NOT NULL,
    period_end TIMESTAMP NOT NULL,
    
    -- Compressed conversation
    summary TEXT NOT NULL,
    
    -- Extracted facts (JSON: ["bought laptop", "planning vacation"])
    important_facts JSONB,
    
    -- Tags for categorization
    tags VARCHAR(255)[],
    
    message_count INTEGER DEFAULT 0,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_user_summaries FOREIGN KEY (user_id) 
        REFERENCES user_preferences(user_id) ON DELETE CASCADE
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_user_prefs_user_id ON user_preferences(user_id);
CREATE INDEX IF NOT EXISTS idx_context_patterns_user_id ON user_context_patterns(user_id);
CREATE INDEX IF NOT EXISTS idx_context_patterns_type ON user_context_patterns(pattern_type);
CREATE INDEX IF NOT EXISTS idx_summaries_user_id_period ON dialogue_summaries(user_id, period_start DESC);
CREATE INDEX IF NOT EXISTS idx_summaries_session ON dialogue_summaries(session_id);

-- Insert default preferences for testing
INSERT INTO user_preferences (user_id, full_name, occupation, communication_style, allow_sarcasm)
VALUES ('denis', 'Denis', 'IT Student & Developer', 'FRIENDLY', true)
ON CONFLICT (user_id) DO NOTHING;
