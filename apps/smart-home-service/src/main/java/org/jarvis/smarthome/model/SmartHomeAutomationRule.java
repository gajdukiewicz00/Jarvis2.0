package org.jarvis.smarthome.model;

/**
 * A simple trigger → action automation rule (e.g. motion on {@code hall_motion}
 * turns on {@code hall_light}). Evaluated by {@code SmartHomeAutomationEngine}
 * whenever a matching sensor reading is ingested.
 *
 * <p>{@code triggerThreshold} is only meaningful for threshold-based events
 * ({@code TEMPERATURE_ABOVE}/{@code BELOW}, {@code HUMIDITY_ABOVE}/{@code BELOW});
 * it is ignored for discrete events such as {@code MOTION_DETECTED}.
 *
 * <p>{@code allowSensitiveActions} is a safety gate: rules that would act on a
 * sensitive device (currently {@link SmartHomeDeviceType#LOCK}) are skipped
 * unless this is explicitly set to {@code true}, so a rule cannot silently
 * unlock a door.
 */
public record SmartHomeAutomationRule(
        String id,
        String name,
        String triggerDeviceId,
        SmartHomeTriggerEvent triggerEvent,
        Double triggerThreshold,
        String actionDeviceId,
        String actionType,
        String actionPayload,
        boolean allowSensitiveActions,
        boolean enabled) {
}
