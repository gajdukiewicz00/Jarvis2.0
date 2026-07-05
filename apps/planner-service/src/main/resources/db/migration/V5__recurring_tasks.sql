-- Recurring tasks (RRULE-lite): daily / weekly / interval recurrence for
-- task templates, plus daily-plan generation bookkeeping to avoid
-- double-generating an occurrence for the same calendar date.

ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS recurrence_rule VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS recurrence_interval_days INTEGER,
    ADD COLUMN IF NOT EXISTS recurrence_anchor_date DATE,
    ADD COLUMN IF NOT EXISTS recurrence_source_task_id BIGINT,
    ADD COLUMN IF NOT EXISTS last_generated_date DATE;

ALTER TABLE tasks
    ADD CONSTRAINT fk_task_recurrence_source FOREIGN KEY (recurrence_source_task_id)
        REFERENCES tasks(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_tasks_recurrence_rule
    ON tasks (user_id, recurrence_rule)
    WHERE recurrence_rule <> 'NONE';

COMMENT ON COLUMN tasks.recurrence_rule IS 'RRULE-lite: NONE, DAILY, WEEKLY, INTERVAL';
COMMENT ON COLUMN tasks.recurrence_interval_days IS 'Used when recurrence_rule = INTERVAL';
COMMENT ON COLUMN tasks.recurrence_anchor_date IS 'Pattern anchor: day-of-week for WEEKLY, start date for INTERVAL';
COMMENT ON COLUMN tasks.recurrence_source_task_id IS 'Set on a generated occurrence, pointing back at its recurring template task';
COMMENT ON COLUMN tasks.last_generated_date IS 'Last date an occurrence was generated for this recurring template (dedup guard)';
