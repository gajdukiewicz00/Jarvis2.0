package org.jarvis.smarthome.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeAutomationEvaluation;
import org.jarvis.smarthome.model.SmartHomeAutomationRule;
import org.jarvis.smarthome.model.SmartHomeAutomationSimulation;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeSensorReading;
import org.jarvis.smarthome.model.SmartHomeSimulatedAction;
import org.jarvis.smarthome.security.SafetyPolicy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates automation rules against incoming sensor readings and, when a
 * rule's trigger matches, dispatches its action through {@link SmartHomeService}
 * as a system actor.
 *
 * <p>Safety gate: a rule that targets a security-critical device type (see
 * {@link SafetyPolicy}) is only executed if
 * {@link SmartHomeAutomationRule#allowSensitiveActions()} is {@code true} —
 * that flag stands in for the explicit confirmation
 * {@code SmartHomeService#executeAction} otherwise requires. This prevents a
 * misconfigured or spoofed sensor reading from silently unlocking a door.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartHomeAutomationEngine {

    /** Synthetic user id automation-triggered actions are attributed to. */
    static final String AUTOMATION_USER_ID = "system:automation";

    private final SmartHomeAutomationRuleRegistry ruleRegistry;
    private final SmartHomeDeviceCatalog catalog;
    private final SmartHomeService smartHomeService;
    private final SafetyPolicy safetyPolicy;

    /**
     * Evaluate all enabled rules whose trigger device matches this reading's
     * device. Returns one evaluation entry per rule whose trigger condition
     * matched (rules that don't apply to this reading are omitted).
     */
    public List<SmartHomeAutomationEvaluation> evaluate(SmartHomeSensorReading reading) {
        List<SmartHomeAutomationEvaluation> evaluations = new ArrayList<>();
        for (SmartHomeAutomationRule rule : ruleRegistry.all()) {
            if (!rule.enabled() || !rule.triggerDeviceId().equals(reading.deviceId())) {
                continue;
            }
            if (!matchesTrigger(rule, reading)) {
                continue;
            }
            evaluations.add(applyRule(rule));
        }
        return evaluations;
    }

    /**
     * Dry-run: evaluate rules matching {@code reading} exactly like
     * {@link #evaluate} but never call {@link SmartHomeService#executeAction} —
     * returns what WOULD happen (device found, action supported, safety gate)
     * without actuating anything.
     */
    public List<SmartHomeAutomationSimulation> simulate(SmartHomeSensorReading reading) {
        List<SmartHomeAutomationSimulation> results = new ArrayList<>();
        for (SmartHomeAutomationRule rule : ruleRegistry.all()) {
            if (!rule.enabled() || !rule.triggerDeviceId().equals(reading.deviceId())) {
                continue;
            }
            if (!matchesTrigger(rule, reading)) {
                continue;
            }
            results.add(planRule(rule));
        }
        return results;
    }

    private SmartHomeAutomationEvaluation applyRule(SmartHomeAutomationRule rule) {
        Optional<SmartHomeDeviceDefinition> actionDevice = catalog.findById(rule.actionDeviceId());
        if (actionDevice.isEmpty()) {
            return new SmartHomeAutomationEvaluation(rule.id(), rule.name(), true, false, false,
                    "Action device not found: " + rule.actionDeviceId());
        }
        if (safetyPolicy.requiresConfirmation(actionDevice.get().type(), rule.allowSensitiveActions())) {
            log.warn("Blocked automation rule {} ({}): security-critical action on {} requires allowSensitiveActions=true",
                    rule.id(), rule.name(), rule.actionDeviceId());
            return new SmartHomeAutomationEvaluation(rule.id(), rule.name(), true, false, true,
                    "Blocked: rule targets a security-critical device (" + actionDevice.get().type()
                            + ") and allowSensitiveActions is false");
        }

        try {
            smartHomeService.executeAction(AUTOMATION_USER_ID, rule.actionDeviceId(),
                    new SmartHomeActionRequest(rule.actionType(), rule.actionPayload()), rule.allowSensitiveActions());
            return new SmartHomeAutomationEvaluation(rule.id(), rule.name(), true, true, false,
                    "Executed " + rule.actionType() + " on " + rule.actionDeviceId());
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new SmartHomeAutomationEvaluation(rule.id(), rule.name(), true, false, false, message);
        }
    }

    private SmartHomeAutomationSimulation planRule(SmartHomeAutomationRule rule) {
        Optional<SmartHomeDeviceDefinition> actionDevice = catalog.findById(rule.actionDeviceId());
        if (actionDevice.isEmpty()) {
            SmartHomeSimulatedAction predicted = new SmartHomeSimulatedAction(
                    rule.actionDeviceId(), rule.actionType(), rule.actionPayload(),
                    false, false, false, false,
                    "Action device not found: " + rule.actionDeviceId());
            return new SmartHomeAutomationSimulation(rule.id(), rule.name(), true, predicted, predicted.message());
        }

        SmartHomeDeviceDefinition device = actionDevice.get();
        boolean needsConfirmation = safetyPolicy.requiresConfirmation(device.type(), rule.allowSensitiveActions());
        SmartHomeSimulatedAction predicted;
        if (needsConfirmation) {
            predicted = new SmartHomeSimulatedAction(rule.actionDeviceId(), rule.actionType(), rule.actionPayload(),
                    true, false, true, false,
                    "Blocked: rule targets a security-critical device (" + device.type()
                            + ") and allowSensitiveActions is false");
        } else {
            boolean actionSupported = rule.actionType() != null && device.supportedActions().contains(rule.actionType());
            String message = actionSupported
                    ? "Would execute " + rule.actionType() + " on " + rule.actionDeviceId() + " (simulation only)"
                    : "Would fail: action " + rule.actionType() + " is not supported by " + rule.actionDeviceId();
            predicted = new SmartHomeSimulatedAction(rule.actionDeviceId(), rule.actionType(), rule.actionPayload(),
                    true, actionSupported, false, actionSupported, message);
        }
        return new SmartHomeAutomationSimulation(rule.id(), rule.name(), true, predicted, predicted.message());
    }

    private static boolean matchesTrigger(SmartHomeAutomationRule rule, SmartHomeSensorReading reading) {
        return switch (rule.triggerEvent()) {
            case MOTION_DETECTED -> isMetric(reading, "MOTION") && reading.value() >= 0.5;
            case MOTION_CLEARED -> isMetric(reading, "MOTION") && reading.value() < 0.5;
            case DOOR_OPENED -> isMetric(reading, "DOOR") && reading.value() >= 0.5;
            case DOOR_CLOSED -> isMetric(reading, "DOOR") && reading.value() < 0.5;
            case TEMPERATURE_ABOVE -> isMetric(reading, "TEMPERATURE") && exceedsThreshold(rule, reading, true);
            case TEMPERATURE_BELOW -> isMetric(reading, "TEMPERATURE") && exceedsThreshold(rule, reading, false);
            case HUMIDITY_ABOVE -> isMetric(reading, "HUMIDITY") && exceedsThreshold(rule, reading, true);
            case HUMIDITY_BELOW -> isMetric(reading, "HUMIDITY") && exceedsThreshold(rule, reading, false);
        };
    }

    private static boolean isMetric(SmartHomeSensorReading reading, String metric) {
        return metric.equalsIgnoreCase(reading.metric());
    }

    private static boolean exceedsThreshold(SmartHomeAutomationRule rule, SmartHomeSensorReading reading, boolean above) {
        if (rule.triggerThreshold() == null) {
            return false;
        }
        return above ? reading.value() > rule.triggerThreshold() : reading.value() < rule.triggerThreshold();
    }
}
