package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.dto.RecommendationDto;
import org.jarvis.planner.model.RecommendationType;
import org.jarvis.planner.model.TaskPriority;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple recommendation engine
 * TODO: Integrate with analytics-service and user-profile for real habit analysis
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationEngine {
    
    public List<RecommendationDto> generateRecommendations(String userId) {
        log.info("Generating recommendations for user: {}", userId);
        
        List<RecommendationDto> recommendations = new ArrayList<>();
        
        // Placeholder recommendations (will be replaced with real analytics)
        recommendations.add(new RecommendationDto(
            RecommendationType.PRODUCTIVITY,
            "Попробуй метод Pomodoro для повышения концентрации",
            TaskPriority.MEDIUM
        ));
        
        recommendations.add(new RecommendationDto(
            RecommendationType.HEALTH,
            "Не забудь про перерыв каждый час для разминки",
            TaskPriority.LOW
        ));
        
        return recommendations;
    }
}
