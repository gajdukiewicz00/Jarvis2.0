package org.jarvis.smarthome.model;

import java.util.List;

/**
 * Full result of resolving a natural-language utterance to a planned
 * smart-home command: the parsed action/payload plus the device(s) it
 * matched in the registry.
 *
 * <p>This is a plan only — hardware is never actuated here. {@code device}
 * is populated only when {@code status} is {@code RESOLVED}. Real actuation
 * still requires an explicit call to the existing device-action endpoint
 * (hardware actuation itself remains gated behind the configured transport).
 */
public record SmartHomeIntentResolution(
        String utterance,
        IntentMatchStatus status,
        double confidence,
        String action,
        String payload,
        SmartHomeDeviceDefinition device,
        List<SmartHomeDeviceDefinition> candidates,
        String message) {

    public SmartHomeIntentResolution {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
