package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.model.PcScenarioDefinition;
import org.jarvis.pccontrol.model.PcScenarioStep;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPcScenarioRegistryTest {

    private static final Set<String> SUPPORTED_STEP_TYPES = Set.of(
            "OPEN_APP",
            "OPEN_URL",
            "HOTKEY",
            "NOTIFY",
            "PLAY_PAUSE",
            "PAUSE",
            "SET_VOLUME",
            "MUTE",
            "WAIT",
            "WINDOW_FOCUS",
            "WINDOW_CLOSE",
            "WINDOW_MINIMIZE",
            "WINDOW_MAXIMIZE",
            "WINDOW_NORMALIZE",
            "WINDOW_RESTORE",
            "MOUSE_MOVE",
            "MOUSE_LEFT_CLICK",
            "MOUSE_RIGHT_CLICK",
            "MOUSE_LEFT_DOWN",
            "MOUSE_LEFT_UP",
            "EMPTY_TRASH",
            "OPEN_OPTICAL_DRIVE",
            "CLOSE_OPTICAL_DRIVE");

    @Test
    void loadsYamlExtensionScenariosWithoutDuplicates() {
        InMemoryPcScenarioRegistry registry = new InMemoryPcScenarioRegistry();

        List<PcScenarioDefinition> scenarios = registry.all();
        assertTrue(scenarios.size() >= 55, "Expected default and migrated legacy scenarios to be loaded");
        assertTrue(registry.findByName("legacy_ppt_delete_slide").isPresent());
        assertTrue(registry.findByName("legacy_draw_circle").isPresent());

        Set<String> names = new LinkedHashSet<>();
        for (PcScenarioDefinition scenario : scenarios) {
            assertFalse(scenario.name() == null || scenario.name().isBlank(), "Scenario name must be present");
            assertTrue(names.add(scenario.name()), "Duplicate scenario name: " + scenario.name());
            for (PcScenarioStep step : scenario.steps()) {
                assertFalse(step.actionType() == null || step.actionType().isBlank(),
                        "Scenario " + scenario.name() + " contains a step without actionType");
                assertTrue(SUPPORTED_STEP_TYPES.contains(step.actionType()),
                        "Scenario " + scenario.name() + " uses unsupported step type " + step.actionType());
            }
        }
    }
}
