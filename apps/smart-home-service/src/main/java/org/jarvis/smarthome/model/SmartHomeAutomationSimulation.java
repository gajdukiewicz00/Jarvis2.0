package org.jarvis.smarthome.model;

/**
 * Dry-run outcome of evaluating one {@link SmartHomeAutomationRule} against a
 * current or supplied sensor reading — mirrors {@link SmartHomeAutomationEvaluation}
 * but is produced by {@code SmartHomeAutomationEngine#simulate} and never
 * actuates the target device.
 */
public record SmartHomeAutomationSimulation(
        String ruleId,
        String ruleName,
        boolean triggered,
        SmartHomeSimulatedAction predictedAction,
        String message) {
}
