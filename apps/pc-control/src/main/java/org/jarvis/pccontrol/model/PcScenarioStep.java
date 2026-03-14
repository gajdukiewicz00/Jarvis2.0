package org.jarvis.pccontrol.model;

import java.util.Map;

public record PcScenarioStep(
        String id,
        String actionType,
        Map<String, String> parameters,
        String description) {

    public PcScenarioStep {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
