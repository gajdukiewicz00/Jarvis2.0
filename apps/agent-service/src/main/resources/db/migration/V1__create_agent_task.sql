-- Agent task persistence for the Postgres-backed AgentTaskStore.
-- Opt-in via jarvis.agent.task-store=postgres (in-memory remains the default store).
-- Mirrors the AgentTask domain record 1:1 so running/failed tasks survive a pod restart.

CREATE TABLE IF NOT EXISTS agent_task (
    task_id               VARCHAR(64) PRIMARY KEY,
    user_id               VARCHAR(255) NOT NULL,
    role                  VARCHAR(32) NOT NULL,
    goal                  TEXT NOT NULL,
    status                VARCHAR(32) NOT NULL,
    permissions_requested VARCHAR(500) NOT NULL DEFAULT '',
    permissions_granted   VARCHAR(500) NOT NULL DEFAULT '',
    sandbox_path          VARCHAR(1000),
    dry_run               BOOLEAN NOT NULL DEFAULT FALSE,
    attempt               INTEGER NOT NULL DEFAULT 0,
    max_retries           INTEGER NOT NULL DEFAULT 1,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    started_at            TIMESTAMP,
    finished_at           TIMESTAMP,
    error_message         TEXT,
    result_summary        TEXT,
    artifacts             TEXT,
    risks                 TEXT,
    correlation_id        VARCHAR(255),
    swarm_id              VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_agent_task_user_id ON agent_task(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_task_swarm_id ON agent_task(swarm_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_agent_task_status ON agent_task(status);

COMMENT ON TABLE agent_task IS 'Persisted agent-swarm task lifecycle (opt-in via jarvis.agent.task-store=postgres)';
