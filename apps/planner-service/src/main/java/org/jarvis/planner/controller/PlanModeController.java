package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.service.EnergyStateService;
import org.jarvis.planner.service.PlanModeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P1 #10 — alternate daily plan "modes": a minimum-viable slice for
 * overloaded or low-energy days, and a deep-work block for focused days.
 */
@RestController
@RequestMapping("/api/v1/planner/plan")
@RequiredArgsConstructor
public class PlanModeController {

    private final PlanModeService planModeService;
    private final EnergyStateService energyStateService;

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
}
