package org.jarvis.smarthome.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Persists a row every time a device action successfully changes state, and
 * serves bounded, most-recent-first pages of that history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceStateHistoryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final DeviceStateHistoryRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Persist one state snapshot for a successfully executed device action. */
    public DeviceStateHistoryEntry record(String userId, String deviceId, String action, String payload,
            Map<String, Object> state, boolean success) {
        DeviceStateHistoryEntry entry = DeviceStateHistoryEntry.builder()
                .userId(userId)
                .deviceId(deviceId)
                .action(action)
                .payload(payload)
                .stateJson(toJson(state))
                .success(success)
                .recordedAt(clock.instant())
                .build();
        return repository.save(entry);
    }

    /**
     * Bounded, most-recent-first page of persisted history for a device, scoped to
     * {@code userId} — callers must never see another user's device history.
     */
    public List<DeviceStateHistoryEntry> history(String userId, String deviceId, int limit) {
        return repository.findByUserIdAndDeviceIdOrderByRecordedAtDesc(userId, deviceId, PageRequest.of(0, clamp(limit)));
    }

    private static int clamp(int limit) {
        int base = limit <= 0 ? DEFAULT_LIMIT : limit;
        return Math.min(base, MAX_LIMIT);
    }

    private String toJson(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(state == null ? Map.of() : state);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize device state to JSON, storing toString() fallback: {}", e.getMessage());
            return String.valueOf(state);
        }
    }
}
