-- Wellness tracking: habits, weight, mood, steps, workouts, notes.
CREATE TABLE IF NOT EXISTS wellness_log (
    id            BIGSERIAL PRIMARY KEY,
    user_id       VARCHAR(255) NOT NULL,
    type          VARCHAR(20)  NOT NULL,
    numeric_value DOUBLE PRECISION,
    text_value    VARCHAR(1000),
    logged_at     TIMESTAMP    NOT NULL,
    day           DATE         NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_wellness_user_day  ON wellness_log (user_id, day);
CREATE INDEX IF NOT EXISTS idx_wellness_user_type ON wellness_log (user_id, type);
