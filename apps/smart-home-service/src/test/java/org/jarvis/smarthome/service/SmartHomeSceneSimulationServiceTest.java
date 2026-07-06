package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.jarvis.smarthome.model.SmartHomeScene;
import org.jarvis.smarthome.model.SmartHomeSceneSimulation;
import org.jarvis.smarthome.model.SmartHomeSimulatedAction;
import org.jarvis.smarthome.security.SafetyPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartHomeSceneSimulationServiceTest {

    private SmartHomeSceneService sceneService;
    private SmartHomeDeviceCatalog catalog;
    private SmartHomeSceneSimulationService simulationService;

    @BeforeEach
    void setUp() {
        sceneService = new SmartHomeSceneService();
        catalog = new SmartHomeDeviceCatalog();
        simulationService = new SmartHomeSceneSimulationService(sceneService, catalog, new SafetyPolicy());
    }

    @Test
    void simulateReturnsNotFoundForUnknownScene() {
        SmartHomeSceneSimulation result = simulationService.simulate("missing", false);

        assertFalse(result.found());
        assertTrue(result.actions().isEmpty());
        assertTrue(result.message().contains("not found"));
    }

    @Test
    void simulateDoesNotActuateAnyDevice() {
        sceneService.save(new SmartHomeScene("night",
                List.of(new SmartHomeScene.SceneStep("kitchen_light", "TURN_OFF", null))));

        SmartHomeSceneSimulation result = simulationService.simulate("night", false);

        assertTrue(result.found());
        assertEquals(1, result.actions().size());
        SmartHomeSimulatedAction action = result.actions().get(0);
        assertTrue(action.deviceFound());
        assertTrue(action.actionSupported());
        assertFalse(action.needsConfirmation());
        assertTrue(action.wouldExecute());
        // The dry-run must never mutate real device state — no SmartHomeService is even wired in.
        assertEquals(false, catalog.findById("kitchen_light").orElseThrow().defaultState().get("power"));
    }

    @Test
    void simulateReportsDeviceNotFound() {
        sceneService.save(new SmartHomeScene("ghost",
                List.of(new SmartHomeScene.SceneStep("missing_device", "TURN_ON", null))));

        SmartHomeSceneSimulation result = simulationService.simulate("ghost", false);

        SmartHomeSimulatedAction action = result.actions().get(0);
        assertFalse(action.deviceFound());
        assertFalse(action.wouldExecute());
        assertTrue(action.message().contains("not found"));
    }

    @Test
    void simulateReportsUnsupportedAction() {
        sceneService.save(new SmartHomeScene("bad-action",
                List.of(new SmartHomeScene.SceneStep("kitchen_light", "SPIN", null))));

        SmartHomeSceneSimulation result = simulationService.simulate("bad-action", false);

        SmartHomeSimulatedAction action = result.actions().get(0);
        assertTrue(action.deviceFound());
        assertFalse(action.actionSupported());
        assertFalse(action.wouldExecute());
    }

    @Test
    void simulateBlocksUnconfirmedLockStep() {
        sceneService.save(new SmartHomeScene("leave-home",
                List.of(new SmartHomeScene.SceneStep("front_door_lock", "LOCK", null))));

        SmartHomeSceneSimulation result = simulationService.simulate("leave-home", false);

        SmartHomeSimulatedAction action = result.actions().get(0);
        assertTrue(action.needsConfirmation());
        assertFalse(action.wouldExecute());
    }

    @Test
    void simulateAllowsLockStepWhenConfirmed() {
        sceneService.save(new SmartHomeScene("leave-home",
                List.of(new SmartHomeScene.SceneStep("front_door_lock", "LOCK", null))));

        SmartHomeSceneSimulation result = simulationService.simulate("leave-home", true);

        SmartHomeSimulatedAction action = result.actions().get(0);
        assertFalse(action.needsConfirmation());
        assertTrue(action.wouldExecute());
    }

    @Test
    void simulateBlocksUnconfirmedDoorAndGarageSteps() {
        catalog.register(new SmartHomeDeviceDefinition(
                "front_door", "Front Door", "Entrance", SmartHomeDeviceType.DOOR,
                List.of("OPEN", "CLOSE"), new LinkedHashMap<>(Map.of("open", false))));
        catalog.register(new SmartHomeDeviceDefinition(
                "main_garage", "Main Garage", "Garage", SmartHomeDeviceType.GARAGE,
                List.of("OPEN", "CLOSE"), new LinkedHashMap<>(Map.of("open", false))));
        sceneService.save(new SmartHomeScene("open-everything", List.of(
                new SmartHomeScene.SceneStep("front_door", "OPEN", null),
                new SmartHomeScene.SceneStep("main_garage", "OPEN", null))));

        SmartHomeSceneSimulation result = simulationService.simulate("open-everything", false);

        assertEquals(2, result.actions().size());
        assertTrue(result.actions().stream().allMatch(SmartHomeSimulatedAction::needsConfirmation));
        assertTrue(result.actions().stream().noneMatch(SmartHomeSimulatedAction::wouldExecute));
    }

    @Test
    void simulateHandlesNullStepsGracefully() {
        sceneService.save(new SmartHomeScene("empty", null));

        SmartHomeSceneSimulation result = simulationService.simulate("empty", false);

        assertTrue(result.found());
        assertTrue(result.actions().isEmpty());
    }
}
