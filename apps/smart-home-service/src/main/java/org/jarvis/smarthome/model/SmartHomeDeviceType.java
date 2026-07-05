package org.jarvis.smarthome.model;

/**
 * Kinds of smart-home devices the service understands.
 *
 * <p>{@code LIGHT}, {@code THERMOSTAT}, {@code LOCK} and {@code SWITCH} are actionable
 * devices with mutable state driven by {@code executeAction}. The {@code *_SENSOR}
 * types are read-only — they report readings through the sensor-ingestion endpoints
 * (see {@code SmartHomeSensorService}) rather than accepting device actions.
 */
public enum SmartHomeDeviceType {
    LIGHT,
    THERMOSTAT,
    LOCK,
    SWITCH,
    TEMPERATURE_SENSOR,
    HUMIDITY_SENSOR,
    MOTION_SENSOR,
    DOOR_SENSOR
}
