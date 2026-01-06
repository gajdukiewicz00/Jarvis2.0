package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.dto.DailyPlanDto;
import org.jarvis.planner.dto.RecommendationDto;
import org.jarvis.planner.service.DailyPlanGenerator;
import org.jarvis.planner.service.WeeklyPlanGenerator;
import org.jarvis.planner.service.RecommendationEngine;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/planner")
@RequiredArgsConstructor
public class PlannerController {
    
    private final DailyPlanGenerator dailyPlanGenerator;
    private final WeeklyPlanGenerator weeklyPlanGenerator;
    private final RecommendationEngine recommendationEngine;
    
    /**
     * Get daily plan
     */
    @GetMapping("/daily")
    public ResponseEntity<DailyPlanDto> getDailyPlan(
            @RequestParam String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate planDate = date != null ? date : LocalDate.now();
        log.info("GET daily plan for user: {} on {}", userId, planDate);
        
        DailyPlanDto plan = dailyPlanGenerator.generatePlan(userId, planDate);
        return ResponseEntity.ok(plan);
    }
    
    /**
     * Get weekly plan
     */
    @GetMapping("/weekly")
    public ResponseEntity<Map<String, Object>> getWeeklyPlan(@RequestParam String userId) {
        log.info("GET weekly plan for user: {}", userId);
        
        Map<String, Object> plan = weeklyPlanGenerator.generateWeeklyPlan(userId);
        return ResponseEntity.ok(plan);
    }
    
    /**
     * Get recommendations
     */
    @GetMapping("/recommendations")
    public ResponseEntity<List<RecommendationDto>> getRecommendations(@RequestParam String userId) {
        log.info("GET recommendations for user: {}", userId);
        
        List<RecommendationDto> recommendations = recommendationEngine.generateRecommendations(userId);
        return ResponseEntity.ok(recommendations);
    }
    
    /**
     * Health check
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Planner service is healthy");
    }
}
