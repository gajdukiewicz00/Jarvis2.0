package org.jarvis.smarthome.model;

/**
 * A single device action a dry-run simulation determined it WOULD take (or
 * why it would not), without ever calling {@code SmartHomeService#executeAction}.
 *
 * <p>{@code wouldExecute} is {@code true} only when the device was found, the
 * action is supported by it, and it is not blocked by
 * {@code org.jarvis.smarthome.security.SafetyPolicy}.
 */
public record SmartHomeSimulatedAction(
        String deviceId,
        String action,
        String payload,
        boolean deviceFound,
        boolean actionSupported,
        boolean needsConfirmation,
        boolean wouldExecute,
        String message) {
}
