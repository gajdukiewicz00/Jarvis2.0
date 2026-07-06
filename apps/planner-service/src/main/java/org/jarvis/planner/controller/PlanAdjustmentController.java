package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.planner.service.PlanAdjustmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * #12 — wellness-aware plan adjustment. Combines sleep/steps/energy signals
 * from life-tracker with the existing energy-aware ranking to suggest and
 * explain a {@link org.jarvis.planner.model.PlanMode} for the day.
 */
@RestController
@RequestMapping("/api/v1/planner/plan")
@RequiredArgsConstructor
public class PlanAdjustmentController {

    private final PlanAdjustmentService planAdjustmentService;

    /** Wellness-adjusted day plan: suggested mode, ranked tasks, and a human explanation of why. */
    @GetMapping("/adjusted")
    public ResponseEntity<Map<String, Object>> adjustedPlan(Authentication authentication,
            @RequestParam(defaultValue = "false") boolean force) {
        String userId = authentication.getName();
        return ResponseEntity.ok(planAdjustmentService.getAdjustedPlan(userId, force));
    }
}
