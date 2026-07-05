package org.jarvis.smarthome.model;

/**
 * Events an automation rule can trigger on, derived from an incoming
 * {@link SmartHomeSensorReading}. The {@code *_ABOVE}/{@code *_BELOW} events
 * are compared against the rule's {@code triggerThreshold}.
 */
public enum SmartHomeTriggerEvent {
    MOTION_DETECTED,
    MOTION_CLEARED,
    DOOR_OPENED,
    DOOR_CLOSED,
    TEMPERATURE_ABOVE,
    TEMPERATURE_BELOW,
    HUMIDITY_ABOVE,
    HUMIDITY_BELOW
}
