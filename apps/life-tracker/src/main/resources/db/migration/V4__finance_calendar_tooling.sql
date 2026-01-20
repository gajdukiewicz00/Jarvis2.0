-- Calendar and finance extensions

ALTER TABLE calendar_event
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS location VARCHAR(255),
    ADD COLUMN IF NOT EXISTS recurrence_rule VARCHAR(255),
    ADD COLUMN IF NOT EXISTS recurrence_until TIMESTAMP,
    ADD COLUMN IF NOT EXISTS timezone VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE expense
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS type VARCHAR(20) DEFAULT 'EXPENSE',
    ADD COLUMN IF NOT EXISTS merchant VARCHAR(255),
    ADD COLUMN IF NOT EXISTS payment_method VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS budget (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    category VARCHAR(100) NOT NULL,
    limit_amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(10),
    period VARCHAR(20) DEFAULT 'MONTHLY',
    start_date DATE,
    end_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS financial_goal (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    name VARCHAR(200) NOT NULL,
    target_amount NUMERIC(19, 2) NOT NULL,
    current_amount NUMERIC(19, 2),
    target_date DATE,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS recurring_transaction (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(10),
    category VARCHAR(100),
    description VARCHAR(500),
    type VARCHAR(20) DEFAULT 'EXPENSE',
    merchant VARCHAR(255),
    interval VARCHAR(20) DEFAULT 'MONTHLY',
    next_run TIMESTAMP,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tool_requests (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    tool_name VARCHAR(100) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    response_body TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_calendar_event_user_time ON calendar_event(user_id, start_time, end_time);
CREATE INDEX IF NOT EXISTS idx_expense_user_date ON expense(user_id, date DESC);
CREATE INDEX IF NOT EXISTS idx_budget_user ON budget(user_id);
CREATE INDEX IF NOT EXISTS idx_goal_user ON financial_goal(user_id);
CREATE INDEX IF NOT EXISTS idx_recurring_user ON recurring_transaction(user_id, active);
CREATE INDEX IF NOT EXISTS idx_tool_requests_user ON tool_requests(user_id, created_at DESC);
