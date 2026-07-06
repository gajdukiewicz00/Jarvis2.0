-- Append-only audit trail of device state changes, one row per successfully
-- executed device action. Queried (bounded, most-recent-first) via
-- GET /api/v1/smarthome/devices/{deviceId}/state-history.
CREATE TABLE device_state_history (
    id BIGSERIAL PRIMARY KEY,
    device_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    action VARCHAR(100) NOT NULL,
    payload VARCHAR(1000),
    state_json TEXT NOT NULL,
    success BOOLEAN NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_device_state_history_device_recorded
    ON device_state_history (device_id, recorded_at DESC);
