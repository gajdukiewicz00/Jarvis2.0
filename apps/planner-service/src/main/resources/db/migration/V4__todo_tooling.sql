-- Todo tooling extensions

ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS tags TEXT,
    ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

CREATE TABLE IF NOT EXISTS tool_requests (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    tool_name VARCHAR(100) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    response_body TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tool_requests_user ON tool_requests(user_id, created_at DESC);
