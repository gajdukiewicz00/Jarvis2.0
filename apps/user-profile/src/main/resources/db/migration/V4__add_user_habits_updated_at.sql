-- Add updated_at column to user_habits to match entity mapping

ALTER TABLE user_habits
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

UPDATE user_habits
SET updated_at = COALESCE(created_at, CURRENT_TIMESTAMP)
WHERE updated_at IS NULL;

ALTER TABLE user_habits
    ALTER COLUMN updated_at SET NOT NULL;
