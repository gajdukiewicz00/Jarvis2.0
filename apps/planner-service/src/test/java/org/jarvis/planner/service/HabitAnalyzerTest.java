package org.jarvis.planner.service;

import org.jarvis.planner.client.AnalyticsClient;
import org.jarvis.planner.client.UserProfileClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HabitAnalyzerTest {

    @Mock
    private AnalyticsClient analyticsClient;

    @Mock
    private UserProfileClient userProfileClient;

    @InjectMocks
    private HabitAnalyzer analyzer;

    @Test
    void analyzeHabitsAddsImprovementRecommendationsWhenThresholdsAreMissed() {
        when(analyticsClient.getAverageSleepHours("user-1")).thenReturn(6.5);
        when(analyticsClient.getWeeklyOvertimeHours("user-1")).thenReturn(11);
        when(userProfileClient.getUserGoals("user-1")).thenReturn(List.of("sleep better", "ship tests"));

        Map<String, Object> analysis = analyzer.analyzeHabits("user-1");
        Map<String, Object> sleep = section(analysis, "sleep");
        Map<String, Object> work = section(analysis, "work");
        Map<String, Object> goals = section(analysis, "goals");

        assertEquals(6.5, sleep.get("averageHours"));
        assertEquals("NEEDS_IMPROVEMENT", sleep.get("status"));
        assertEquals("Попробуй ложиться на 30 минут раньше", sleep.get("recommendation"));

        assertEquals(11, work.get("weeklyOvertime"));
        assertEquals("OVERWORKED", work.get("status"));
        assertEquals("Слишком много переработок. Нужен отдых.", work.get("recommendation"));

        assertEquals(2, goals.get("activeGoals"));
        assertEquals(List.of("sleep better", "ship tests"), goals.get("goals"));
    }

    @Test
    void analyzeHabitsKeepsHealthyAndBalancedStatesWithoutRecommendations() {
        when(analyticsClient.getAverageSleepHours("user-2")).thenReturn(7.5);
        when(analyticsClient.getWeeklyOvertimeHours("user-2")).thenReturn(5);
        when(userProfileClient.getUserGoals("user-2")).thenReturn(List.of());

        Map<String, Object> analysis = analyzer.analyzeHabits("user-2");
        Map<String, Object> sleep = section(analysis, "sleep");
        Map<String, Object> work = section(analysis, "work");

        assertEquals("GOOD", sleep.get("status"));
        assertFalse(sleep.containsKey("recommendation"));
        assertEquals("BALANCED", work.get("status"));
        assertFalse(work.containsKey("recommendation"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> analysis, String key) {
        return (Map<String, Object>) analysis.get(key);
    }
}
