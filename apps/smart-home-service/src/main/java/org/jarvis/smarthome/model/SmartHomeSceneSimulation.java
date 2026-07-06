package org.jarvis.smarthome.model;

import java.util.List;

/**
 * Dry-run outcome of activating a scene: the actions it would take, one per
 * step, without executing any of them. Produced by
 * {@code SmartHomeSceneSimulationService#simulate}.
 */
public record SmartHomeSceneSimulation(
        String sceneName,
        boolean found,
        List<SmartHomeSimulatedAction> actions,
        String message) {

    public SmartHomeSceneSimulation {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
