package org.jarvis.smarthome.model;

import java.util.List;

/**
 * A named scene — a set of device actions applied together
 * (e.g. "movie night" → dim lights, lock door). In-memory, runtime-defined.
 */
public record SmartHomeScene(String name, List<SceneStep> steps) {

    public record SceneStep(String deviceId, String action, String payload) {
    }
}
