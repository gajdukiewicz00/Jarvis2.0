-- Active time tracking state and user scoping

ALTER TABLE time_record
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(255);

CREATE TABLE IF NOT EXISTS active_time_record (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    activity VARCHAR(200) NOT NULL,
    category VARCHAR(100) NOT NULL,
    start_time TIMESTAMP NOT NULL,
    CONSTRAINT uk_active_time_record_user UNIQUE (user_id)
);

CREATE INDEX IF NOT EXISTS idx_active_time_record_user ON active_time_record(user_id);
