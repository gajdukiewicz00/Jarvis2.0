-- V2: idempotent-replay support for task submission, plus the retention sweep's
-- query paths.
--
-- The userId+createdAt (idx_agent_task_user_id) and status (idx_agent_task_status)
-- hot query paths already have indices from V1 — the retention sweep reuses
-- idx_agent_task_status for its "find every finished task" scan, so no new index is
-- needed there. This migration only adds what V1 could not have anticipated: the
-- idempotency_key column itself and its lookup index (one client-supplied key is
-- scoped to a single user, see AgentTaskJpaRepository#findByUserIdAndIdempotencyKey).

ALTER TABLE agent_task
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

-- Not a UNIQUE index: standard SQL treats every NULL as distinct, so this alone would
-- not reject a genuine duplicate submit racing the read-then-write check in
-- AgentTaskService#submit. Enforcing that race-free would need a real upsert/locking
-- strategy, which is out of scope here — this index only speeds up the lookup.
CREATE INDEX IF NOT EXISTS idx_agent_task_user_idempotency
    ON agent_task(user_id, idempotency_key);

COMMENT ON COLUMN agent_task.idempotency_key IS
    'Optional client-supplied key (CreateTaskRequest#idempotencyKey). A repeated submit with the same user+key returns the existing task instead of starting a new run.';
