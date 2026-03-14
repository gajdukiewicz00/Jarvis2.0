package org.jarvis.planner.service;

import org.jarvis.planner.dto.RecommendationDto;
import org.jarvis.planner.model.RecommendationType;
import org.jarvis.planner.model.TaskPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class RecommendationEngineTest {

    private final RecommendationEngine engine = new RecommendationEngine();

    @Test
    void generateRecommendationsReturnsDefaultRecommendationsInStableOrder() {
        List<RecommendationDto> recommendations = engine.generateRecommendations("user-1");

        assertEquals(2, recommendations.size());

        RecommendationDto productivity = recommendations.get(0);
        assertEquals(RecommendationType.PRODUCTIVITY, productivity.getType());
        assertEquals("Попробуй метод Pomodoro для повышения концентрации", productivity.getMessage());
        assertEquals(TaskPriority.MEDIUM, productivity.getPriority());

        RecommendationDto health = recommendations.get(1);
        assertEquals(RecommendationType.HEALTH, health.getType());
        assertEquals("Не забудь про перерыв каждый час для разминки", health.getMessage());
        assertEquals(TaskPriority.LOW, health.getPriority());
    }
}
