package org.jarvis.planner.service;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.model.EnergyLevel;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * B3 — per-user current energy state. In-memory and transient by design (no
 * migration); defaults to {@link EnergyLevel#NORMAL}. A future iteration can
 * persist this or derive it from sleep/steps; for now it is set explicitly
 * (voice/API) and optionally seeded by callers.
 */
@Slf4j
@Service
public class EnergyStateService {

    private final ConcurrentHashMap<String, EnergyLevel> state = new ConcurrentHashMap<>();

    public EnergyLevel set(String userId, EnergyLevel level) {
        EnergyLevel effective = level == null ? EnergyLevel.NORMAL : level;
        state.put(userId, effective);
        log.info("energy state for {} set to {}", userId, effective);
        return effective;
    }

    public EnergyLevel get(String userId) {
        return state.getOrDefault(userId, EnergyLevel.NORMAL);
    }
}
