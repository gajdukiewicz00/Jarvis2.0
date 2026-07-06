package org.jarvis.smarthome.model;

/**
 * Kinds of smart-home devices the service understands.
 *
 * <p>{@code LIGHT}, {@code THERMOSTAT}, {@code LOCK}, {@code SWITCH}, {@code DOOR}
 * and {@code GARAGE} are actionable devices with mutable state driven by
 * {@code executeAction}. The {@code *_SENSOR} types are read-only — they report
 * readings through the sensor-ingestion endpoints (see {@code SmartHomeSensorService})
 * rather than accepting device actions. {@code DOOR_SENSOR} is the read-only
 * counterpart of {@code DOOR} (e.g. a contact sensor reporting open/closed),
 * distinct from the actionable {@code DOOR} device (e.g. a smart strike/deadbolt).
 *
 * <p>{@code LOCK}, {@code DOOR} and {@code GARAGE} are security-critical: see
 * {@code org.jarvis.smarthome.security.SafetyPolicy}. Actions on these types
 * require an explicit confirmation flag or {@code executeAction} returns a
 * {@code needsConfirmation} result instead of actuating the device.
 */
public enum SmartHomeDeviceType {
    LIGHT,
    THERMOSTAT,
    LOCK,
    SWITCH,
    DOOR,
    GARAGE,
    TEMPERATURE_SENSOR,
    HUMIDITY_SENSOR,
    MOTION_SENSOR,
    DOOR_SENSOR
}
