-- Media Service: Postgres-backed job store (opt-in via jarvis.media.job-store=postgres).
-- The whole MediaJob is stored as a single JSON payload (mirrors FileBackedMediaJobStore's
-- one-file-per-job approach) so the schema never needs to change when job "details" shape
-- evolves. Only the columns actually queried on (id, user_id, created_at) are broken out.

CREATE TABLE IF NOT EXISTS media_jobs (
    id         VARCHAR(64)  NOT NULL PRIMARY KEY,
    user_id    VARCHAR(128) NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    payload    TEXT         NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_media_jobs_user_id ON media_jobs (user_id);
CREATE INDEX IF NOT EXISTS idx_media_jobs_created_at ON media_jobs (created_at);
