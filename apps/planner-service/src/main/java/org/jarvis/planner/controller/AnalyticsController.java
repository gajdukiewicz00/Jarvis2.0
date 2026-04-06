package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.service.HabitAnalyzer;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
    public ResponseEntity<Map<String, Object>> getHabits(
            Authentication authentication,
            @RequestHeader(value = "X-Smoke-Run-Id", required = false) String smokeRunId) {
        String userId = authentication.getName();
        log.info("GET habits analysis for user: {}, smokeRunId={}", userId, smokeRunId);
        
        Map<String, Object> analysis = habitAnalyzer.analyzeHabits(userId, smokeRunId);
        return ResponseEntity.ok(analysis);
    }
    
    /**
     * Get insights and recommendations
     */
    @GetMapping("/insights")
    public ResponseEntity<Map<String, Object>> getInsights(
            Authentication authentication,
            @RequestHeader(value = "X-Smoke-Run-Id", required = false) String smokeRunId) {
        String userId = authentication.getName();
        log.info("GET insights for user: {}, smokeRunId={}", userId, smokeRunId);
        
        Map<String, Object> insights = habitAnalyzer.analyzeHabits(userId, smokeRunId);
        
        // Add additional insights metadata
        insights.put("generatedAt", java.time.Instant.now().toString());
        insights.put("userId", userId);
        
        return ResponseEntity.ok(insights);
    }
}
