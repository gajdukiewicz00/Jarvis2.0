package org.jarvis.smarthome.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeScene;
import org.jarvis.smarthome.model.SmartHomeSceneSimulation;
import org.jarvis.smarthome.model.SmartHomeSimulatedAction;
import org.jarvis.smarthome.security.SafetyPolicy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Dry-run evaluation of a scene: for every step, determines whether it would
 * execute — device found, action supported by it, not blocked by
 * {@link SafetyPolicy} — without ever calling {@link SmartHomeService#executeAction}.
 */
@Service
@RequiredArgsConstructor
public class SmartHomeSceneSimulationService {

    private final SmartHomeSceneService sceneService;
    private final SmartHomeDeviceCatalog catalog;
    private final SafetyPolicy safetyPolicy;

    /**
     * @param confirmed whether the (hypothetical) activation would carry an
     *                  explicit confirmation flag — mirrors
     *                  {@code SmartHomeService#executeAction}'s {@code confirmed}
     *                  parameter for security-critical device types.
     */
    public SmartHomeSceneSimulation simulate(String sceneName, boolean confirmed) {
        Optional<SmartHomeScene> found = sceneService.find(sceneName);
        if (found.isEmpty()) {
            return new SmartHomeSceneSimulation(sceneName, false, List.of(), "Scene not found: " + sceneName);
        }

        SmartHomeScene scene = found.get();
        List<SmartHomeScene.SceneStep> steps = scene.steps() == null ? List.of() : scene.steps();
        List<SmartHomeSimulatedAction> actions = new ArrayList<>();
        for (SmartHomeScene.SceneStep step : steps) {
            actions.add(planStep(step, confirmed));
        }
        long wouldExecuteCount = actions.stream().filter(SmartHomeSimulatedAction::wouldExecute).count();
        String message = "Simulated " + actions.size() + " step(s); " + wouldExecuteCount + " would execute";
        return new SmartHomeSceneSimulation(sceneName, true, actions, message);
    }

    private SmartHomeSimulatedAction planStep(SmartHomeScene.SceneStep step, boolean confirmed) {
        Optional<SmartHomeDeviceDefinition> device = catalog.findById(step.deviceId());
        if (device.isEmpty()) {
            return new SmartHomeSimulatedAction(step.deviceId(), step.action(), step.payload(),
                    false, false, false, false, "Device not found: " + step.deviceId());
        }

        SmartHomeDeviceDefinition def = device.get();
        if (safetyPolicy.requiresConfirmation(def.type(), confirmed)) {
            return new SmartHomeSimulatedAction(step.deviceId(), step.action(), step.payload(),
                    true, false, true, false,
                    "Blocked: " + def.type() + " is a security-critical device type and needs an explicit confirmation");
        }

        String normalizedAction = normalize(step.action());
        boolean actionSupported = normalizedAction != null && def.supportedActions().contains(normalizedAction);
        String message = actionSupported
                ? "Would execute " + normalizedAction + " on " + step.deviceId() + " (simulation only)"
                : "Would fail: action " + step.action() + " is not supported by " + step.deviceId();
        return new SmartHomeSimulatedAction(step.deviceId(), step.action(), step.payload(),
                true, actionSupported, false, actionSupported, message);
    }

    private static String normalize(String action) {
        return action == null ? null : action.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
