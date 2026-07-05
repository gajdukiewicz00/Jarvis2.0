package org.jarvis.smarthome.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeAutomationEvaluation;
import org.jarvis.smarthome.model.SmartHomeAutomationRule;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.jarvis.smarthome.model.SmartHomeSensorReading;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Evaluates automation rules against incoming sensor readings and, when a
 * rule's trigger matches, dispatches its action through {@link SmartHomeService}
 * as a system actor.
 *
 * <p>Safety gate: a rule that targets a sensitive device type (currently
 * {@link SmartHomeDeviceType#LOCK}) is only executed if
 * {@link SmartHomeAutomationRule#allowSensitiveActions()} is {@code true}.
 * This prevents a misconfigured or spoofed sensor reading from silently
 * unlocking a door.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartHomeAutomationEngine {

    /** Synthetic user id automation-triggered actions are attributed to. */
    static final String AUTOMATION_USER_ID = "system:automation";

    private static final Set<SmartHomeDeviceType> SENSITIVE_TYPES = Set.of(SmartHomeDeviceType.LOCK);

    private final SmartHomeAutomationRuleRegistry ruleRegistry;
    private final SmartHomeDeviceCatalog catalog;
    private final SmartHomeService smartHomeService;

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

    private SmartHomeAutomationEvaluation applyRule(SmartHomeAutomationRule rule) {
        Optional<SmartHomeDeviceDefinition> actionDevice = catalog.findById(rule.actionDeviceId());
        if (actionDevice.isEmpty()) {
            return new SmartHomeAutomationEvaluation(rule.id(), rule.name(), true, false, false,
                    "Action device not found: " + rule.actionDeviceId());
        }
        if (isSensitive(actionDevice.get()) && !rule.allowSensitiveActions()) {
            log.warn("Blocked automation rule {} ({}): sensitive action on {} requires allowSensitiveActions=true",
                    rule.id(), rule.name(), rule.actionDeviceId());
            return new SmartHomeAutomationEvaluation(rule.id(), rule.name(), true, false, true,
                    "Blocked: rule targets a sensitive device (" + actionDevice.get().type()
                            + ") and allowSensitiveActions is false");
        }

        try {
            smartHomeService.executeAction(AUTOMATION_USER_ID, rule.actionDeviceId(),
                    new SmartHomeActionRequest(rule.actionType(), rule.actionPayload()));
            return new SmartHomeAutomationEvaluation(rule.id(), rule.name(), true, true, false,
                    "Executed " + rule.actionType() + " on " + rule.actionDeviceId());
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new SmartHomeAutomationEvaluation(rule.id(), rule.name(), true, false, false, message);
        }
    }

    private static boolean isSensitive(SmartHomeDeviceDefinition device) {
        return SENSITIVE_TYPES.contains(device.type());
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
