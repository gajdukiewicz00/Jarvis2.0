package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.dto.DailyPlanDto;
import org.jarvis.planner.dto.RecommendationDto;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.repository.TaskRepository;
import org.jarvis.planner.service.DailyPlanGenerator;
import org.jarvis.planner.service.WeeklyPlanGenerator;
import org.jarvis.planner.service.RecommendationEngine;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
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
    private final TaskRepository taskRepository;
    
    /**
     * Get daily plan
     */
    @GetMapping("/daily")
    public ResponseEntity<DailyPlanDto> getDailyPlan(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        String userId = authentication.getName();
        LocalDate planDate = date != null ? date : LocalDate.now();
        log.info("GET daily plan for user: {} on {}", userId, planDate);
        
        DailyPlanDto plan = dailyPlanGenerator.generatePlan(userId, planDate);
        return ResponseEntity.ok(plan);
    }
    
    /**
     * Tomorrow's plan — same generator as /daily, defaulted to tomorrow's date.
     */
    @GetMapping("/tomorrow")
    public ResponseEntity<DailyPlanDto> getTomorrowPlan(Authentication authentication) {
        String userId = authentication.getName();
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        log.info("GET tomorrow plan for user: {} on {}", userId, tomorrow);

        DailyPlanDto plan = dailyPlanGenerator.generatePlan(userId, tomorrow);
        return ResponseEntity.ok(plan);
    }

    /**
     * Get weekly plan
     */
    @GetMapping("/weekly")
    public ResponseEntity<Map<String, Object>> getWeeklyPlan(Authentication authentication) {
        String userId = authentication.getName();
        log.info("GET weekly plan for user: {}", userId);
        
        Map<String, Object> plan = weeklyPlanGenerator.generateWeeklyPlan(userId);
        return ResponseEntity.ok(plan);
    }
    
    /**
     * Get recommendations
     */
    @GetMapping("/recommendations")
    public ResponseEntity<List<RecommendationDto>> getRecommendations(Authentication authentication) {
        String userId = authentication.getName();
        log.info("GET recommendations for user: {}", userId);
        
        List<RecommendationDto> recommendations = recommendationEngine.generateRecommendations(userId);
        return ResponseEntity.ok(recommendations);
    }

    /**
     * "What's the one thing to focus on now" — the single highest-priority open task.
     */
    @GetMapping("/focus")
    public ResponseEntity<Map<String, Object>> getFocus(Authentication authentication) {
        String userId = authentication.getName();
        List<Task> active = taskRepository.findActiveTasks(userId);
        if (active.isEmpty()) {
            return ResponseEntity.ok(Map.of("hasFocus", false,
                    "message", "Никаких срочных задач, сэр. Можно выдохнуть."));
        }
        Task top = active.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hasFocus", true);
        out.put("taskId", top.getId());
        out.put("title", top.getTitle());
        out.put("priority", top.getPriority());
        out.put("dueDate", top.getDueDate());
        out.put("openTasks", active.size());
        out.put("message", "Главное сейчас, сэр: " + top.getTitle());
        return ResponseEntity.ok(out);
    }

    /**
     * End-of-day review — what got done, what is still open, what is overdue.
     */
    @GetMapping("/evening-review")
    public ResponseEntity<Map<String, Object>> eveningReview(Authentication authentication) {
        String userId = authentication.getName();
        long done = taskRepository.countByUserIdAndStatus(userId, TaskStatus.DONE);
        List<Task> active = taskRepository.findActiveTasks(userId);
        Instant now = Instant.now();
        long overdue = active.stream()
                .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(now))
                .count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doneTotal", done);
        out.put("stillOpen", active.size());
        out.put("overdue", overdue);
        out.put("topOpen", active.stream().limit(3).map(Task::getTitle).toList());
        out.put("message", overdue > 0
                ? "Вечерний обзор, сэр: просрочено " + overdue + ", открыто " + active.size() + "."
                : "Вечерний обзор, сэр: открыто " + active.size() + " задач, просрочек нет.");
        return ResponseEntity.ok(out);
    }

    /**
     * Weekly review — what got completed in the last 7 days vs. what's still
     * open/overdue. Complements /evening-review (daily) at a week's cadence.
     */
    @GetMapping("/weekly-review")
    public ResponseEntity<Map<String, Object>> weeklyReview(Authentication authentication) {
        String userId = authentication.getName();
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        List<Task> completed = taskRepository.findByUserIdAndStatusAndCompletedAtAfter(userId, TaskStatus.DONE, weekAgo);
        List<Task> active = taskRepository.findActiveTasks(userId);
        Instant now = Instant.now();
        long overdue = active.stream()
                .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(now))
                .count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weekStart", LocalDate.now().minusDays(7));
        out.put("weekEnd", LocalDate.now());
        out.put("completedCount", completed.size());
        out.put("completedTitles", completed.stream().map(Task::getTitle).limit(10).toList());
        out.put("stillOpen", active.size());
        out.put("overdue", overdue);
        out.put("message", "Обзор недели, сэр: выполнено " + completed.size() + ", открыто " + active.size()
                + (overdue > 0 ? ", просрочено " + overdue + "." : ", просрочек нет."));
        return ResponseEntity.ok(out);
    }

    /**
     * Health check
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Planner service is healthy");
    }
}
