package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeSceneActivation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartHomeSceneHistoryServiceTest {

    private SmartHomeSceneHistoryService historyService;

    @BeforeEach
    void setUp() {
        historyService = new SmartHomeSceneHistoryService();
    }

    @Test
    void recordStoresActivationAndReturnsIt() {
        SmartHomeSceneActivation activation = new SmartHomeSceneActivation(
                "movie_night", "user-1", Instant.parse("2026-03-14T10:00:00Z"), 2, 2, 0);

        SmartHomeSceneActivation recorded = historyService.record(activation);

        assertEquals(activation, recorded);
        assertEquals(1, historyService.all().size());
    }

    @Test
    void recentReturnsMostRecentFirst() {
        historyService.record(new SmartHomeSceneActivation(
                "first", "user-1", Instant.parse("2026-03-14T10:00:00Z"), 1, 1, 0));
        historyService.record(new SmartHomeSceneActivation(
                "second", "user-1", Instant.parse("2026-03-14T10:05:00Z"), 1, 1, 0));

        List<SmartHomeSceneActivation> recent = historyService.recent(10);

        assertEquals("second", recent.get(0).sceneName());
        assertEquals("first", recent.get(1).sceneName());
    }

    @Test
    void recentHonorsLimit() {
        for (int i = 0; i < 5; i++) {
            historyService.record(new SmartHomeSceneActivation(
                    "scene-" + i, "user-1", Instant.now(), 1, 1, 0));
        }

        assertEquals(2, historyService.recent(2).size());
    }

    @Test
    void recentWithNegativeLimitReturnsEmptyList() {
        historyService.record(new SmartHomeSceneActivation(
                "scene", "user-1", Instant.now(), 1, 1, 0));

        assertTrue(historyService.recent(-1).isEmpty());
    }

    @Test
    void historyIsBoundedByMaxRetention() {
        for (int i = 0; i < 250; i++) {
            historyService.record(new SmartHomeSceneActivation(
                    "scene-" + i, "user-1", Instant.now(), 1, 1, 0));
        }

        assertEquals(200, historyService.all().size());
        assertEquals("scene-249", historyService.all().get(0).sceneName());
    }
}
