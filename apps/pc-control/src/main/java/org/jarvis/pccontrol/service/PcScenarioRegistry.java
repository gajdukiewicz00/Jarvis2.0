package org.jarvis.pccontrol.service;

import org.jarvis.pccontrol.model.PcScenarioDefinition;

import java.util.List;
import java.util.Optional;

public interface PcScenarioRegistry {

    Optional<PcScenarioDefinition> findByName(String scenarioName);

    List<PcScenarioDefinition> all();
}
