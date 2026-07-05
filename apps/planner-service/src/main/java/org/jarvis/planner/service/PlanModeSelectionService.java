package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.model.PlanMode;
import org.jarvis.planner.model.UserPlanMode;
import org.jarvis.planner.repository.UserPlanModeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persisted per-user {@link PlanMode} selection — explicit and sticky (kept
 * until changed), unlike {@link EnergyStateService}'s transient energy
 * signal. Read by {@link org.jarvis.planner.controller.PlanModeController}
 * to feed {@link EnergyAwareRanker} alongside the user's current energy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanModeSelectionService {

    private final UserPlanModeRepository userPlanModeRepository;

    @Transactional
    public PlanMode setMode(String userId, PlanMode mode) {
        PlanMode effective = mode == null ? PlanMode.NORMAL : mode;
        UserPlanMode entity = userPlanModeRepository.findByUserId(userId).orElseGet(() -> {
            UserPlanMode created = new UserPlanMode();
            created.setUserId(userId);
            return created;
        });
        entity.setPlanMode(effective);
        userPlanModeRepository.save(entity);
        log.info("plan mode for {} set to {}", userId, effective);
        return effective;
    }

    public PlanMode getMode(String userId) {
        return userPlanModeRepository.findByUserId(userId)
                .map(UserPlanMode::getPlanMode)
                .orElse(PlanMode.NORMAL);
    }
}
