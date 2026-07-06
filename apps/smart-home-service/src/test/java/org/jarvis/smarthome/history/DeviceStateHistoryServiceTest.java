package org.jarvis.smarthome.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceStateHistoryServiceTest {

    @Mock
    private DeviceStateHistoryRepository repository;

    private DeviceStateHistoryService service;

    @BeforeEach
    void setUp() {
        service = new DeviceStateHistoryService(repository, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-03-14T10:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void recordSerializesStateAndSavesEntry() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeviceStateHistoryEntry saved = service.record("user-a", "kitchen_light", "TOGGLE", null,
                Map.of("power", true), true);

        assertEquals("user-a", saved.getUserId());
        assertEquals("kitchen_light", saved.getDeviceId());
        assertEquals("TOGGLE", saved.getAction());
        assertTrue(saved.isSuccess());
        assertTrue(saved.getStateJson().contains("power"));
        assertEquals(Instant.parse("2026-03-14T10:30:00Z"), saved.getRecordedAt());
    }

    @Test
    void historyClampsLimitToConfiguredMaximum() {
        service.history("kitchen_light", 10_000);

        verify(repository).findByDeviceIdOrderByRecordedAtDesc(eq("kitchen_light"),
                argThat(page -> page.getPageSize() == 500));
    }

    @Test
    void historyAppliesDefaultLimitWhenNonPositive() {
        service.history("kitchen_light", 0);

        verify(repository).findByDeviceIdOrderByRecordedAtDesc(eq("kitchen_light"),
                argThat(page -> page.getPageSize() == 50));
    }

    @Test
    void historyDelegatesToRepositoryForNormalLimit() {
        List<DeviceStateHistoryEntry> expected = List.of();
        when(repository.findByDeviceIdOrderByRecordedAtDesc(eq("kitchen_light"), any())).thenReturn(expected);

        List<DeviceStateHistoryEntry> result = service.history("kitchen_light", 5);

        assertSame(expected, result);
        verify(repository).findByDeviceIdOrderByRecordedAtDesc(eq("kitchen_light"),
                argThat(page -> page.getPageSize() == 5));
    }
}
