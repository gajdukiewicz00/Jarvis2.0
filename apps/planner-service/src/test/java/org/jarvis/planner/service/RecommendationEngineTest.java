package org.jarvis.planner.service;

import org.jarvis.planner.dto.RecommendationDto;
import org.jarvis.planner.model.RecommendationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationEngineTest {

    @Mock
    private HabitAnalyzer habitAnalyzer;

    @InjectMocks
    private RecommendationEngine engine;

    @Test
    void generateRecommendationsUsesHabitSignalsInsteadOfStaticPlaceholders() {
        when(habitAnalyzer.analyzeHabits("user-1")).thenReturn(Map.of(
                "sleep", Map.of("status", "NEEDS_IMPROVEMENT", "averageHours", 6.2),
                "work", Map.of("status", "OVERWORKED", "weeklyOvertime", 11),
                "goals", Map.of("goals", List.of("Ship core backend"))));

        List<RecommendationDto> recommendations = engine.generateRecommendations("user-1");

        assertEquals(3, recommendations.size());
        assertEquals(RecommendationType.HEALTH, recommendations.get(0).getType());
        assertEquals(RecommendationType.PRODUCTIVITY, recommendations.get(1).getType());
        assertEquals(RecommendationType.PRODUCTIVITY, recommendations.get(2).getType());
    }

    @Test
    void generateRecommendationsFallsBackToStableBaselineWhenSignalsAreHealthy() {
        when(habitAnalyzer.analyzeHabits("user-2")).thenReturn(Map.of(
                "sleep", Map.of("status", "GOOD", "averageHours", 7.5),
                "work", Map.of("status", "BALANCED", "weeklyOvertime", 2),
                "goals", Map.of("goals", List.of())));

        List<RecommendationDto> recommendations = engine.generateRecommendations("user-2");

        assertEquals(2, recommendations.size());
        assertEquals(RecommendationType.PRODUCTIVITY, recommendations.get(0).getType());
        assertEquals(RecommendationType.HEALTH, recommendations.get(1).getType());
    }
}
