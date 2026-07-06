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
    void findByUserIdAndDeviceIdOrderByRecordedAtDescReturnsMostRecentFirstScopedToDevice() {
        persist("user-a", "kitchen_light", Instant.parse("2026-03-14T10:00:00Z"));
        persist("user-a", "kitchen_light", Instant.parse("2026-03-14T11:00:00Z"));
        persist("user-a", "front_door_lock", Instant.parse("2026-03-14T12:00:00Z"));
        entityManager.flush();

        List<DeviceStateHistoryEntry> page = repository.findByUserIdAndDeviceIdOrderByRecordedAtDesc(
                "user-a", "kitchen_light", PageRequest.of(0, 10));

        assertEquals(2, page.size());
        assertEquals(Instant.parse("2026-03-14T11:00:00Z"), page.get(0).getRecordedAt());
        assertEquals(Instant.parse("2026-03-14T10:00:00Z"), page.get(1).getRecordedAt());
    }

    @Test
    void findByUserIdAndDeviceIdOrderByRecordedAtDescRespectsPageSize() {
        for (int i = 0; i < 5; i++) {
            persist("user-a", "kitchen_light", Instant.parse("2026-03-14T10:0" + i + ":00Z"));
        }
        entityManager.flush();

        List<DeviceStateHistoryEntry> page = repository.findByUserIdAndDeviceIdOrderByRecordedAtDesc(
                "user-a", "kitchen_light", PageRequest.of(0, 2));

        assertEquals(2, page.size());
    }

    /** Regression test for the cross-user data leak (FINDING #5): a device's history rows
     * recorded by one user must never be returned when a different user queries the same
     * deviceId. */
    @Test
    void findByUserIdAndDeviceIdOrderByRecordedAtDescDoesNotReturnAnotherUsersEntriesForSameDevice() {
        persist("user-a", "kitchen_light", Instant.parse("2026-03-14T10:00:00Z"));
        persist("user-b", "kitchen_light", Instant.parse("2026-03-14T11:00:00Z"));
        entityManager.flush();

        List<DeviceStateHistoryEntry> userAPage = repository.findByUserIdAndDeviceIdOrderByRecordedAtDesc(
                "user-a", "kitchen_light", PageRequest.of(0, 10));
        List<DeviceStateHistoryEntry> userBPage = repository.findByUserIdAndDeviceIdOrderByRecordedAtDesc(
                "user-b", "kitchen_light", PageRequest.of(0, 10));

        assertEquals(1, userAPage.size());
        assertEquals("user-a", userAPage.get(0).getUserId());
        assertEquals(1, userBPage.size());
        assertEquals("user-b", userBPage.get(0).getUserId());
    }

    private void persist(String userId, String deviceId, Instant recordedAt) {
        DeviceStateHistoryEntry entry = DeviceStateHistoryEntry.builder()
                .deviceId(deviceId)
                .userId(userId)
                .action("TOGGLE")
                .payload(null)
                .stateJson("{\"power\":true}")
                .success(true)
                .recordedAt(recordedAt)
                .build();
        entityManager.persist(entry);
    }
}
