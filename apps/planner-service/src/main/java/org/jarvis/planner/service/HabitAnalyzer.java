package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.client.AnalyticsClient;
import org.jarvis.planner.client.UserProfileClient;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces rule-based planning signals from analytics-service and user-profile.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HabitAnalyzer {
    
    private final AnalyticsClient analyticsClient;
    private final UserProfileClient userProfileClient;
    
    public Map<String, Object> analyzeHabits(String userId) {
        return analyzeHabits(userId, null);
    }

    public Map<String, Object> analyzeHabits(String userId, String smokeRunId) {
        log.info("Analyzing habits for user: {}, smokeRunId={}", userId, smokeRunId);
        
        Map<String, Object> analysis = new HashMap<>();
        UserProfileClient.PlanningContext planningContext = userProfileClient.getPlanningContext(userId);
        List<String> activeGoals = planningContext.activeGoalTitles();
        List<String> priorityCategories = planningContext.priorityCategories();
        List<String> morningHabits = planningContext.habitNamesForTime("morning");
        List<String> eveningHabits = planningContext.habitNamesForTime("evening");
        analysis.put("analysisMode", "RULE_BASED_DERIVED");
        analysis.put("dataSources", List.of("analytics-service", "user-profile"));
        
        // Sleep analysis
        Double avgSleep = smokeRunId == null
                ? analyticsClient.getAverageSleepHours(userId)
                : analyticsClient.getAverageSleepHours(userId, smokeRunId);
        Map<String, Object> sleepAnalysis = new HashMap<>();
        sleepAnalysis.put("averageHours", avgSleep);
        sleepAnalysis.put("status", avgSleep == null ? "UNKNOWN" : avgSleep >= 7.0 ? "GOOD" : "NEEDS_IMPROVEMENT");
        if (avgSleep != null && avgSleep < 7.0) {
            sleepAnalysis.put("recommendation", "Попробуй ложиться на 30 минут раньше");
        }
        analysis.put("sleep", sleepAnalysis);
        
        // Work balance analysis
        Integer overtime = smokeRunId == null
                ? analyticsClient.getWeeklyOvertimeHours(userId)
                : analyticsClient.getWeeklyOvertimeHours(userId, smokeRunId);
        Map<String, Object> workAnalysis = new HashMap<>();
        workAnalysis.put("weeklyOvertime", overtime);
        workAnalysis.put("status", overtime == null ? "UNKNOWN" : overtime <= 5 ? "BALANCED" : "OVERWORKED");
        if (overtime != null && overtime > 10) {
            workAnalysis.put("recommendation", "Слишком много переработок. Нужен отдых.");
        }
        analysis.put("work", workAnalysis);
        
        // Profile-derived context
        Map<String, Object> goalsAnalysis = new HashMap<>();
        goalsAnalysis.put("activeGoals", activeGoals.size());
        goalsAnalysis.put("goals", activeGoals);
        analysis.put("goals", goalsAnalysis);

        Map<String, Object> profileAnalysis = new HashMap<>();
        profileAnalysis.put("priorityCategories", priorityCategories);
        profileAnalysis.put("morningHabits", morningHabits);
        profileAnalysis.put("eveningHabits", eveningHabits);
        profileAnalysis.put("timezone", planningContext.timezone());
        analysis.put("profile", profileAnalysis);
        
        return analysis;
    }
}
