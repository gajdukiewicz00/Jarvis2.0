package org.jarvis.smarthome.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One persisted row of a device's state after a successfully executed
 * action — the append-only audit trail queried by
 * {@code GET /api/v1/smarthome/devices/{deviceId}/state-history}.
 *
 * <p>{@code stateJson} is the device's full state snapshot (as returned to
 * the caller in {@code SmartHomeActionResult}) serialized to JSON text, so a
 * schema migration is not needed every time a device type gains a new state
 * field.
 */
@Entity
@Table(name = "device_state_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceStateHistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String action;

    @Column
    private String payload;

    @Column(name = "state_json", nullable = false, columnDefinition = "TEXT")
    private String stateJson;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
