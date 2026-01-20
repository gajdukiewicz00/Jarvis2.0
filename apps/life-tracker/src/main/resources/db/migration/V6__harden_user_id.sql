-- Backfill and enforce user_id non-null in core tables

UPDATE calendar_event
SET user_id = 'legacy'
WHERE user_id IS NULL;

UPDATE expense
SET user_id = 'legacy'
WHERE user_id IS NULL;

UPDATE time_record
SET user_id = 'legacy'
WHERE user_id IS NULL;

ALTER TABLE calendar_event
    ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE expense
    ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE time_record
    ALTER COLUMN user_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_calendar_event_user_time
    ON calendar_event(user_id, start_time, end_time);

CREATE INDEX IF NOT EXISTS idx_expense_user_date
    ON expense(user_id, date DESC);

CREATE INDEX IF NOT EXISTS idx_time_record_user_start
    ON time_record(user_id, start_time DESC);
