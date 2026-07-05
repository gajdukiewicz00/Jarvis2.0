package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeAutomationEvaluation;
import org.jarvis.smarthome.model.SmartHomeAutomationRule;
import org.jarvis.smarthome.model.SmartHomeSensorReading;
import org.jarvis.smarthome.model.SmartHomeTriggerEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SmartHomeAutomationEngineTest {

    @Mock
    private SmartHomeService smartHomeService;

    private SmartHomeAutomationRuleRegistry ruleRegistry;
    private SmartHomeDeviceCatalog catalog;
    private SmartHomeAutomationEngine engine;

    @BeforeEach
    void setUp() {
        ruleRegistry = new SmartHomeAutomationRuleRegistry();
        catalog = new SmartHomeDeviceCatalog();
        engine = new SmartHomeAutomationEngine(ruleRegistry, catalog, smartHomeService);
    }

    @Test
    void motionTriggerExecutesLightAction() {
        ruleRegistry.save(new SmartHomeAutomationRule(
                "motion-light", "Motion turns on kitchen light", "hall_motion",
                SmartHomeTriggerEvent.MOTION_DETECTED, null, "kitchen_light", "TURN_ON", null,
                false, true));

        List<SmartHomeAutomationEvaluation> evaluations = engine.evaluate(
                new SmartHomeSensorReading("hall_motion", "MOTION", 1.0, null, Instant.now()));

        assertEquals(1, evaluations.size());
        SmartHomeAutomationEvaluation evaluation = evaluations.get(0);
        assertTrue(evaluation.triggered());
        assertTrue(evaluation.executed());
        assertFalse(evaluation.blockedBySafetyGate());
        verify(smartHomeService).executeAction(
                SmartHomeAutomationEngine.AUTOMATION_USER_ID, "kitchen_light",
                new SmartHomeActionRequest("TURN_ON", null));
    }

    @Test
    void motionClearedDoesNotTriggerMotionDetectedRule() {
        ruleRegistry.save(new SmartHomeAutomationRule(
                "motion-light", "Motion turns on kitchen light", "hall_motion",
                SmartHomeTriggerEvent.MOTION_DETECTED, null, "kitchen_light", "TURN_ON", null,
                false, true));

        List<SmartHomeAutomationEvaluation> evaluations = engine.evaluate(
                new SmartHomeSensorReading("hall_motion", "MOTION", 0.0, null, Instant.now()));

        assertTrue(evaluations.isEmpty());
        verify(smartHomeService, never()).executeAction(anyString(), anyString(), any());
    }

    @Test
    void disabledRuleIsNeverEvaluated() {
        ruleRegistry.save(new SmartHomeAutomationRule(
                "motion-light", "Motion turns on kitchen light", "hall_motion",
                SmartHomeTriggerEvent.MOTION_DETECTED, null, "kitchen_light", "TURN_ON", null,
                false, false));

        List<SmartHomeAutomationEvaluation> evaluations = engine.evaluate(
                new SmartHomeSensorReading("hall_motion", "MOTION", 1.0, null, Instant.now()));

        assertTrue(evaluations.isEmpty());
    }

    @Test
    void ruleForDifferentDeviceIsIgnored() {
        ruleRegistry.save(new SmartHomeAutomationRule(
                "motion-light", "Motion turns on kitchen light", "hall_motion",
                SmartHomeTriggerEvent.MOTION_DETECTED, null, "kitchen_light", "TURN_ON", null,
                false, true));

        List<SmartHomeAutomationEvaluation> evaluations = engine.evaluate(
                new SmartHomeSensorReading("other_motion", "MOTION", 1.0, null, Instant.now()));

        assertTrue(evaluations.isEmpty());
    }

    @Test
    void sensitiveActionIsBlockedWithoutExplicitPermission() {
        ruleRegistry.save(new SmartHomeAutomationRule(
                "door-unlock", "Door sensor unlocks front door", "front_door_sensor",
                SmartHomeTriggerEvent.DOOR_OPENED, null, "front_door_lock", "UNLOCK", null,
                false, true));

        List<SmartHomeAutomationEvaluation> evaluations = engine.evaluate(
                new SmartHomeSensorReading("front_door_sensor", "DOOR", 1.0, null, Instant.now()));

        assertEquals(1, evaluations.size());
        SmartHomeAutomationEvaluation evaluation = evaluations.get(0);
        assertTrue(evaluation.triggered());
        assertFalse(evaluation.executed());
        assertTrue(evaluation.blockedBySafetyGate());
        verify(smartHomeService, never()).executeAction(anyString(), anyString(), any());
    }

    @Test
    void sensitiveActionExecutesWhenExplicitlyAllowed() {
        ruleRegistry.save(new SmartHomeAutomationRule(
                "door-unlock", "Door sensor unlocks front door", "front_door_sensor",
                SmartHomeTriggerEvent.DOOR_OPENED, null, "front_door_lock", "UNLOCK", null,
                true, true));

        List<SmartHomeAutomationEvaluation> evaluations = engine.evaluate(
                new SmartHomeSensorReading("front_door_sensor", "DOOR", 1.0, null, Instant.now()));

        assertEquals(1, evaluations.size());
        assertTrue(evaluations.get(0).executed());
        verify(smartHomeService).executeAction(
                SmartHomeAutomationEngine.AUTOMATION_USER_ID, "front_door_lock",
                new SmartHomeActionRequest("UNLOCK", null));
    }

    @Test
    void temperatureAboveThresholdTriggersAction() {
        ruleRegistry.save(new SmartHomeAutomationRule(
                "cooling", "High temperature turns on fan switch", "hall_temp_sensor",
                SmartHomeTriggerEvent.TEMPERATURE_ABOVE, 25.0, "kitchen_light", "TURN_ON", null,
                false, true));

        List<SmartHomeAutomationEvaluation> below = engine.evaluate(
                new SmartHomeSensorReading("hall_temp_sensor", "TEMPERATURE", 24.0, "C", Instant.now()));
        assertTrue(below.isEmpty());

        List<SmartHomeAutomationEvaluation> above = engine.evaluate(
                new SmartHomeSensorReading("hall_temp_sensor", "TEMPERATURE", 26.0, "C", Instant.now()));
        assertEquals(1, above.size());
        assertTrue(above.get(0).executed());
    }

    @Test
    void unknownActionDeviceProducesUnexecutedEvaluation() {
        ruleRegistry.save(new SmartHomeAutomationRule(
                "ghost-rule", "Targets a device that does not exist", "hall_motion",
                SmartHomeTriggerEvent.MOTION_DETECTED, null, "missing_device", "TURN_ON", null,
                false, true));

        List<SmartHomeAutomationEvaluation> evaluations = engine.evaluate(
                new SmartHomeSensorReading("hall_motion", "MOTION", 1.0, null, Instant.now()));

        assertEquals(1, evaluations.size());
        assertFalse(evaluations.get(0).executed());
        assertFalse(evaluations.get(0).blockedBySafetyGate());
        assertTrue(evaluations.get(0).message().contains("not found"));
    }
}
