package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.PlanMode;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.repository.TaskRepository;
import org.jarvis.planner.service.EnergyAwareRanker;
import org.jarvis.planner.service.EnergyStateService;
import org.jarvis.planner.service.PlanModeSelectionService;
import org.jarvis.planner.service.PlanModeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1 #10 — alternate daily plan "modes": a minimum-viable slice for
 * overloaded or low-energy days, and a deep-work block for focused days.
 * Also exposes a persisted, explicit {@link PlanMode} selection (P1 —
 * plan-mode-aware ranking) that feeds {@link EnergyAwareRanker} alongside
 * the user's current energy, independent from the computed modes above.
 */
@RestController
@RequestMapping("/api/v1/planner/plan")
@RequiredArgsConstructor
public class PlanModeController {

    private final PlanModeService planModeService;
    private final EnergyStateService energyStateService;
    private final PlanModeSelectionService planModeSelectionService;
    private final EnergyAwareRanker ranker;
    private final TaskRepository taskRepository;

    /** Just the essentials — a small, energy-ranked slice for overloaded/low-capacity days. */
    @GetMapping("/minimum-viable-day")
    public ResponseEntity<Map<String, Object>> minimumViableDay(Authentication authentication) {
        String userId = authentication.getName();
        EnergyLevel energy = energyStateService.get(userId);
        return ResponseEntity.ok(planModeService.minimumViableDay(userId, energy));
    }

    /** One focused block on the hardest available task, sized to a deep-work session. */
    @GetMapping("/deep-work-block")
    public ResponseEntity<Map<String, Object>> deepWorkBlock(Authentication authentication) {
        String userId = authentication.getName();
        EnergyLevel energy = energyStateService.get(userId);
        return ResponseEntity.ok(planModeService.deepWorkBlock(userId, energy));
    }

    /** Persist the user's explicit plan-mode selection (kept until changed). */
    @PostMapping("/mode")
    public ResponseEntity<Map<String, Object>> setMode(Authentication authentication,
            @RequestBody PlanModeRequest body) {
        String userId = authentication.getName();
        PlanMode mode = PlanMode.fromText(body == null ? null : body.mode());
        PlanMode saved = planModeSelectionService.setMode(userId, mode);
        return ResponseEntity.ok(Map.of("mode", saved.name()));
    }

    /** Read back the user's currently persisted plan-mode selection (defaults to NORMAL). */
    @GetMapping("/mode")
    public ResponseEntity<Map<String, Object>> getMode(Authentication authentication) {
        PlanMode mode = planModeSelectionService.getMode(authentication.getName());
        return ResponseEntity.ok(Map.of("mode", mode.name()));
    }

    /** Active tasks ranked by current energy plus the persisted plan-mode selection. */
    @GetMapping("/by-mode")
    public ResponseEntity<Map<String, Object>> planByMode(Authentication authentication,
            @RequestParam(defaultValue = "false") boolean force) {
        String userId = authentication.getName();
        PlanMode mode = planModeSelectionService.getMode(userId);
        EnergyLevel energy = energyStateService.get(userId);
        List<Task> ranked = ranker.rank(taskRepository.findActiveTasks(userId), energy, force, mode);

        List<Map<String, Object>> tasks = ranked.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.getId());
            m.put("title", t.getTitle());
            m.put("priority", t.getPriority());
            m.put("estimatedDuration", t.getEstimatedDuration());
            m.put("dueDate", t.getDueDate());
            m.put("deadlinePressure", ranker.deadlineLabel(t));
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode.name());
        out.put("energy", energy.name());
        out.put("tasks", tasks);
        return ResponseEntity.ok(out);
    }

    public record PlanModeRequest(String mode) {}
}
