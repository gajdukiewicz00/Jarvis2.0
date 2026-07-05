package org.jarvis.smarthome.model;

/**
 * Outcome of evaluating one {@link SmartHomeAutomationRule} against an
 * incoming sensor reading.
 */
public record SmartHomeAutomationEvaluation(
        String ruleId,
        String ruleName,
        boolean triggered,
        boolean executed,
        boolean blockedBySafetyGate,
        String message) {
}
