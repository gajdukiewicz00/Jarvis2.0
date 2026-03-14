package org.jarvis.pccontrol.model;

import java.util.List;

public record PcScenarioDefinition(
        String name,
        String description,
        List<PcScenarioStep> steps) {

    public PcScenarioDefinition {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
