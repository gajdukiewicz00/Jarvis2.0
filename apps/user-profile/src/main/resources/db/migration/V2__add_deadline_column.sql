-- V2__add_deadline_column.sql
-- ============================================================================
-- Добавляем колонку deadline в таблицу user_goals
-- Hibernate entity UserGoal ожидает эту колонку, но V1 миграция её не создавала
-- ============================================================================

ALTER TABLE user_goals ADD COLUMN IF NOT EXISTS deadline TIMESTAMP;

-- Комментарий для документации
COMMENT ON COLUMN user_goals.deadline IS 'Deadline for goal completion (optional)';

