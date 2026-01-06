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
 * Analyzes user habits (sleep, work, finances) for recommendations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HabitAnalyzer {
    
    private final AnalyticsClient analyticsClient;
    private final UserProfileClient userProfileClient;
    
    public Map<String, Object> analyzeHabits(String userId) {
        log.info("Analyzing habits for user: {}", userId);
        
        Map<String, Object> analysis = new HashMap<>();
        
        // Sleep analysis
        Double avgSleep = analyticsClient.getAverageSleepHours(userId);
        Map<String, Object> sleepAnalysis = new HashMap<>();
        sleepAnalysis.put("averageHours", avgSleep);
        sleepAnalysis.put("status", avgSleep >= 7.0 ? "GOOD" : "NEEDS_IMPROVEMENT");
        if (avgSleep < 7.0) {
            sleepAnalysis.put("recommendation", "Попробуй ложиться на 30 минут раньше");
        }
        analysis.put("sleep", sleepAnalysis);
        
        // Work balance analysis
        Integer overtime = analyticsClient.getWeeklyOvertimeHours(userId);
        Map<String, Object> workAnalysis = new HashMap<>();
        workAnalysis.put("weeklyOvertime", overtime);
        workAnalysis.put("status", overtime <= 5 ? "BALANCED" : "OVERWORKED");
        if (overtime > 10) {
            workAnalysis.put("recommendation", "Слишком много переработок. Нужен отдых.");
        }
        analysis.put("work", workAnalysis);
        
        // Goals progress
        List<String> goals = userProfileClient.getUserGoals(userId);
        Map<String, Object> goalsAnalysis = new HashMap<>();
        goalsAnalysis.put("activeGoals", goals.size());
        goalsAnalysis.put("goals", goals);
        analysis.put("goals", goalsAnalysis);
        
        return analysis;
    }
}
