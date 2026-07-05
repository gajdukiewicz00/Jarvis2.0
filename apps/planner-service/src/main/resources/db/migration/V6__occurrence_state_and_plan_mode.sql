-- Per-occurrence skip/complete state for recurring tasks (skipping or
-- completing a single generated occurrence never touches the recurring
-- template, so future occurrences keep generating on schedule), plus a
-- persisted per-user plan-mode selection that feeds the energy-aware ranker
-- (org.jarvis.planner.service.EnergyAwareRanker).

ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS skipped_at TIMESTAMP;

COMMENT ON COLUMN tasks.skipped_at IS 'Set when a recurring occurrence is explicitly skipped (status=SKIPPED) without ending the series';

CREATE TABLE IF NOT EXISTS user_plan_modes (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,

    plan_mode VARCHAR(30) NOT NULL DEFAULT 'NORMAL',

    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_user_plan_mode_user UNIQUE (user_id)
);

COMMENT ON TABLE user_plan_modes IS 'Persisted per-user plan-mode selection (normal/deep_work/recovery/study/minimum_viable_day) feeding EnergyAwareRanker';
COMMENT ON COLUMN user_plan_modes.plan_mode IS 'NORMAL, DEEP_WORK, RECOVERY, STUDY, or MINIMUM_VIABLE_DAY';
