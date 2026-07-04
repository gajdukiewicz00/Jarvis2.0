package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.repository.TaskRepository;
import org.jarvis.planner.service.EnergyAwareRanker;
import org.jarvis.planner.service.EnergyStateService;
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
 * B3 — energy-aware planning surface. Set energy (voice/API), then get
 * recommendations adapted to it ("what should I do now").
 */
@RestController
@RequestMapping("/api/v1/planner")
@RequiredArgsConstructor
public class EnergyController {

    private final EnergyStateService energyStateService;
    private final EnergyAwareRanker ranker;
    private final TaskRepository taskRepository;

    @PostMapping("/energy")
    public ResponseEntity<Map<String, Object>> setEnergy(Authentication authentication,
            @RequestBody EnergyRequest body) {
        String userId = authentication.getName();
        EnergyLevel level = EnergyLevel.fromText(body == null ? null : body.level());
        energyStateService.set(userId, level);
        return ResponseEntity.ok(Map.of("energy", level.name(), "message", ack(level)));
    }

    @GetMapping("/energy")
    public ResponseEntity<Map<String, Object>> getEnergy(Authentication authentication) {
        return ResponseEntity.ok(Map.of("energy", energyStateService.get(authentication.getName()).name()));
    }

    /** "Что мне делать сейчас?" — top task adapted to current energy. */
    @GetMapping("/next-task")
    public ResponseEntity<Map<String, Object>> nextTask(Authentication authentication,
            @RequestParam(defaultValue = "false") boolean force) {
        String userId = authentication.getName();
        EnergyLevel energy = energyStateService.get(userId);
        List<Task> ranked = ranker.rank(taskRepository.findActiveTasks(userId), energy, force);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("energy", energy.name());
        out.put("force", force);
        if (ranked.isEmpty()) {
            out.put("hasTask", false);
            out.put("message", energy == EnergyLevel.EXHAUSTED
                    ? "Задач нет, сэр. Отдыхайте." : "Никаких задач, сэр.");
            return ResponseEntity.ok(out);
        }
        Task top = ranked.get(0);
        out.put("hasTask", true);
        out.put("taskId", top.getId());
        out.put("title", top.getTitle());
        out.put("priority", top.getPriority());
        out.put("estimatedDuration", top.getEstimatedDuration());
        out.put("explanation", ranker.explain(top, energy));
        out.put("openTasks", ranked.size());
        return ResponseEntity.ok(out);
    }

    /** Full day plan, energy-ranked. */
    @GetMapping("/plan-by-energy")
    public ResponseEntity<Map<String, Object>> planByEnergy(Authentication authentication,
            @RequestParam(defaultValue = "false") boolean force) {
        String userId = authentication.getName();
        EnergyLevel energy = energyStateService.get(userId);
        List<Task> ranked = ranker.rank(taskRepository.findActiveTasks(userId), energy, force);
        List<Map<String, Object>> tasks = ranked.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.getId());
            m.put("title", t.getTitle());
            m.put("priority", t.getPriority());
            m.put("estimatedDuration", t.getEstimatedDuration());
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("energy", energy.name());
        out.put("tasks", tasks);
        out.put("explanation", ranked.isEmpty() ? "Задач нет, сэр." : ranker.explain(ranked.get(0), energy));
        return ResponseEntity.ok(out);
    }

    private String ack(EnergyLevel level) {
        return switch (level) {
            case HIGH -> "Принято, сэр — вы полны сил. Подберу задачи посложнее.";
            case NORMAL -> "Принято, сэр. Сбалансированный план.";
            case LOW -> "Понял, сэр — энергии немного. Начнём с лёгкого.";
            case EXHAUSTED -> "Понял, сэр. Режим минимума — только лёгкое и отдых.";
        };
    }

    public record EnergyRequest(String level) {}
}
