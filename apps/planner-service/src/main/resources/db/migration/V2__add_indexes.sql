-- Add performance indexes for tasks table
-- NOTE: expense indexes removed - expense table belongs to life-tracker, not planner-service

-- Index for filtering active tasks by deadline
CREATE INDEX IF NOT EXISTS idx_tasks_active_deadline
    ON tasks(deadline)
    WHERE status <> 'DONE';

-- Index for querying tasks by priority and deadline  
CREATE INDEX IF NOT EXISTS idx_tasks_priority_deadline
    ON tasks(priority, deadline);

-- Index for user-specific task queries by status
CREATE INDEX IF NOT EXISTS idx_tasks_user_id_status
    ON tasks(user_id, status);
