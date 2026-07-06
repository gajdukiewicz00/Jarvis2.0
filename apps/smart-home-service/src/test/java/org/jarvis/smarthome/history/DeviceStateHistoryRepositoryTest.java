package org.jarvis.smarthome.history;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("test")
class DeviceStateHistoryRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DeviceStateHistoryRepository repository;

    @Test
    void findByDeviceIdOrderByRecordedAtDescReturnsMostRecentFirstScopedToDevice() {
        persist("kitchen_light", Instant.parse("2026-03-14T10:00:00Z"));
        persist("kitchen_light", Instant.parse("2026-03-14T11:00:00Z"));
        persist("front_door_lock", Instant.parse("2026-03-14T12:00:00Z"));
        entityManager.flush();

        List<DeviceStateHistoryEntry> page = repository.findByDeviceIdOrderByRecordedAtDesc(
                "kitchen_light", PageRequest.of(0, 10));

        assertEquals(2, page.size());
        assertEquals(Instant.parse("2026-03-14T11:00:00Z"), page.get(0).getRecordedAt());
        assertEquals(Instant.parse("2026-03-14T10:00:00Z"), page.get(1).getRecordedAt());
    }

    @Test
    void findByDeviceIdOrderByRecordedAtDescRespectsPageSize() {
        for (int i = 0; i < 5; i++) {
            persist("kitchen_light", Instant.parse("2026-03-14T10:0" + i + ":00Z"));
        }
        entityManager.flush();

        List<DeviceStateHistoryEntry> page = repository.findByDeviceIdOrderByRecordedAtDesc(
                "kitchen_light", PageRequest.of(0, 2));

        assertEquals(2, page.size());
    }

    private void persist(String deviceId, Instant recordedAt) {
        DeviceStateHistoryEntry entry = DeviceStateHistoryEntry.builder()
                .deviceId(deviceId)
                .userId("user-a")
                .action("TOGGLE")
                .payload(null)
                .stateJson("{\"power\":true}")
                .success(true)
                .recordedAt(recordedAt)
                .build();
        entityManager.persist(entry);
    }
}
