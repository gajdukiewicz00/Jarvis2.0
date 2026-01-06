-- Planner Service Schema

-- Tasks table
CREATE TABLE IF NOT EXISTS tasks (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    
    title VARCHAR(500) NOT NULL,
    description TEXT,
    
    category VARCHAR(100) DEFAULT 'PERSONAL',
    priority VARCHAR(50) DEFAULT 'MEDIUM',
    status VARCHAR(50) DEFAULT 'TODO',
    
    deadline TIMESTAMP,
    estimated_duration INTEGER, -- minutes
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

-- Reminders table
CREATE TABLE IF NOT EXISTS reminders (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    
    message TEXT NOT NULL,
    reminder_time TIMESTAMP NOT NULL,
    
    reminder_type VARCHAR(50) DEFAULT 'ONCE',
    recurring_pattern VARCHAR(255),
    
    status VARCHAR(50) DEFAULT 'ACTIVE',
    
    linked_task_id BIGINT,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    triggered_at TIMESTAMP,
    
    CONSTRAINT fk_reminder_task FOREIGN KEY (linked_task_id) 
        REFERENCES tasks(id) ON DELETE SET NULL
);

-- Daily plans table
CREATE TABLE IF NOT EXISTS daily_plans (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    
    plan_date DATE NOT NULL,
    
    plan_json JSONB NOT NULL,
    
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    confirmed BOOLEAN DEFAULT false,
    
    CONSTRAINT uk_daily_plan_user_date UNIQUE (user_id, plan_date)
);

-- Weekly plans table
CREATE TABLE IF NOT EXISTS weekly_plans (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    
    week_start DATE NOT NULL,
    week_end DATE NOT NULL,
    
    plan_json JSONB NOT NULL,
    
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uk_weekly_plan_user_week UNIQUE (user_id, week_start)
);

-- Recommendations table
CREATE TABLE IF NOT EXISTS recommendations (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    
    recommendation_type VARCHAR(100) NOT NULL,
    
    title VARCHAR(500),
    message TEXT NOT NULL,
    
    priority VARCHAR(50) DEFAULT 'LOW',
    
    status VARCHAR(50) DEFAULT 'PENDING',
    
    data JSONB,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    responded_at TIMESTAMP
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_tasks_user_status ON tasks(user_id, status);
CREATE INDEX IF NOT EXISTS idx_tasks_deadline ON tasks(deadline) WHERE status != 'DONE';
CREATE INDEX IF NOT EXISTS idx_tasks_priority ON tasks(priority, deadline);

CREATE INDEX IF NOT EXISTS idx_reminders_time ON reminders(reminder_time, status);
CREATE INDEX IF NOT EXISTS idx_reminders_user ON reminders(user_id, status);

CREATE INDEX IF NOT EXISTS idx_daily_plans_date ON daily_plans(user_id, plan_date DESC);

CREATE INDEX IF NOT EXISTS idx_weekly_plans_week ON weekly_plans(user_id, week_start DESC);

CREATE INDEX IF NOT EXISTS idx_recommendations_status ON recommendations(user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_recommendations_type ON recommendations(recommendation_type);

-- Comments for documentation
COMMENT ON TABLE tasks IS 'User tasks with priorities, deadlines, and categories';
COMMENT ON TABLE reminders IS 'Scheduled reminders with optional recurring patterns';
COMMENT ON TABLE daily_plans IS 'Generated daily plans stored as JSON структура';
COMMENT ON TABLE weekly_plans IS 'Generated weekly plans with distributed tasks';
COMMENT ON TABLE recommendations IS 'Personalized recommendations based on habit analytics';
