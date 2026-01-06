package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.service.HabitAnalyzer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Analytics controller for habit insights
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/planner/analytics")
@RequiredArgsConstructor
public class AnalyticsController {
    
    private final HabitAnalyzer habitAnalyzer;
    
    /**
     * Get habit analysis
     */
    @GetMapping("/habits")
    public ResponseEntity<Map<String, Object>> getHabits(@RequestParam String userId) {
        log.info("GET habits analysis for user: {}", userId);
        
        Map<String, Object> analysis = habitAnalyzer.analyzeHabits(userId);
        return ResponseEntity.ok(analysis);
    }
    
    /**
     * Get insights and recommendations
     */
    @GetMapping("/insights")
    public ResponseEntity<Map<String, Object>> getInsights(@RequestParam String userId) {
        log.info("GET insights for user: {}", userId);
        
        Map<String, Object> insights = habitAnalyzer.analyzeHabits(userId);
        
        // Add additional insights metadata
        insights.put("generatedAt", java.time.Instant.now().toString());
        insights.put("userId", userId);
        
        return ResponseEntity.ok(insights);
    }
}
