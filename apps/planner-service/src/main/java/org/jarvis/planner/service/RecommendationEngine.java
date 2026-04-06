package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.dto.RecommendationDto;
import org.jarvis.planner.model.RecommendationType;
import org.jarvis.planner.model.TaskPriority;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Recommendation engine backed by the planner's real analytics and user-profile
 * contracts instead of static placeholder copy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationEngine {

    private final HabitAnalyzer habitAnalyzer;

    public List<RecommendationDto> generateRecommendations(String userId) {
        log.info("Generating recommendations for user: {}", userId);

        List<RecommendationDto> recommendations = new ArrayList<>();
        Map<String, Object> analysis = habitAnalyzer.analyzeHabits(userId);

        Map<String, Object> sleep = section(analysis, "sleep");
        if ("NEEDS_IMPROVEMENT".equals(sleep.get("status"))) {
            recommendations.add(new RecommendationDto(
                    RecommendationType.HEALTH,
                    "Средний сон ниже целевого. Сегодня лучше сместить вечерние задачи и лечь раньше.",
                    TaskPriority.HIGH));
        }

        Map<String, Object> work = section(analysis, "work");
        Integer overtime = work.get("weeklyOvertime") instanceof Number number ? number.intValue() : null;
        if (overtime != null && overtime > 5) {
            recommendations.add(new RecommendationDto(
                    RecommendationType.PRODUCTIVITY,
                    "Переработка за неделю выросла. Запланируй один облегчённый блок и короткий перерыв в ближайшие часы.",
                    overtime > 10 ? TaskPriority.HIGH : TaskPriority.MEDIUM));
        }

        Map<String, Object> goals = section(analysis, "goals");
        Object rawGoals = goals.get("goals");
        if (rawGoals instanceof List<?> goalList && !goalList.isEmpty()) {
            String topGoal = String.valueOf(goalList.get(0));
            recommendations.add(new RecommendationDto(
                    RecommendationType.PRODUCTIVITY,
                    "Выдели сегодня отдельный слот под цель: " + topGoal,
                    TaskPriority.MEDIUM));
        }

        Map<String, Object> profile = section(analysis, "profile");
        Object rawPriorities = profile.get("priorityCategories");
        if (rawPriorities instanceof List<?> priorityList && !priorityList.isEmpty()) {
            String topPriority = String.valueOf(priorityList.get(0));
            recommendations.add(new RecommendationDto(
                    RecommendationType.PRODUCTIVITY,
                    "Не размывай день между задачами. Защити отдельный блок под приоритет: " + topPriority,
                    TaskPriority.MEDIUM));
        }

        if (recommendations.isEmpty()) {
            recommendations.add(new RecommendationDto(
                    RecommendationType.PRODUCTIVITY,
                    "Текущий ритм выглядит стабильным. Сохрани один защищённый фокус-блок без отвлечений.",
                    TaskPriority.MEDIUM));
            recommendations.add(new RecommendationDto(
                    RecommendationType.HEALTH,
                    "Баланс в норме. Держи короткие перерывы каждый час, чтобы не копить усталость.",
                    TaskPriority.LOW));
        }

        return recommendations;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> analysis, String key) {
        Object section = analysis.get(key);
        return section instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
