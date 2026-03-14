package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.model.PcScenarioDefinition;
import org.jarvis.pccontrol.model.PcScenarioStep;
import org.jarvis.pccontrol.service.PcScenarioRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class InMemoryPcScenarioRegistry implements PcScenarioRegistry {

    private final List<PcScenarioDefinition> scenarios = List.of(
            new PcScenarioDefinition(
                    "work",
                    "Open primary work tools and confirm focus mode.",
                    List.of(
                            new PcScenarioStep("open-code", "OPEN_APP", Map.of("app", "code"), "Open VS Code"),
                            new PcScenarioStep("open-browser", "OPEN_APP", Map.of("app", "browser"), "Open browser"),
                            new PcScenarioStep("notify", "NOTIFY",
                                    Map.of("title", "Work Mode", "message", "Work scenario activated"),
                                    "Notify that work mode is active"))),
            new PcScenarioDefinition(
                    "rest",
                    "Resume media playback at a comfortable volume.",
                    List.of(
                            new PcScenarioStep("resume-media", "PLAY_PAUSE", Map.of(), "Toggle playback"),
                            new PcScenarioStep("set-volume", "SET_VOLUME", Map.of("level", "35"), "Lower volume"),
                            new PcScenarioStep("notify", "NOTIFY",
                                    Map.of("title", "Rest Mode", "message", "Rest scenario activated"),
                                    "Notify that rest mode is active"))),
            new PcScenarioDefinition(
                    "focus",
                    "Minimize distractions and confirm the scenario.",
                    List.of(
                            new PcScenarioStep("show-desktop", "HOTKEY", Map.of("keyCombination", "Super+d"),
                                    "Show desktop"),
                            new PcScenarioStep("notify", "NOTIFY",
                                    Map.of("title", "Focus Mode", "message", "Focus scenario activated"),
                                    "Notify that focus mode is active"))),
            new PcScenarioDefinition(
                    "party",
                    "Raise the energy with playback and louder volume.",
                    List.of(
                            new PcScenarioStep("resume-media", "PLAY_PAUSE", Map.of(), "Toggle playback"),
                            new PcScenarioStep("set-volume", "SET_VOLUME", Map.of("level", "70"), "Raise volume"),
                            new PcScenarioStep("notify", "NOTIFY",
                                    Map.of("title", "Party Mode", "message", "Party scenario activated"),
                                    "Notify that party mode is active"))),
            new PcScenarioDefinition(
                    "clean_slate",
                    "Clear the workspace and confirm the state.",
                    List.of(
                            new PcScenarioStep("show-desktop", "HOTKEY", Map.of("keyCombination", "Super+d"),
                                    "Show desktop"),
                            new PcScenarioStep("notify", "NOTIFY",
                                    Map.of("title", "Clean Slate", "message", "Desktop cleared"),
                                    "Notify that the desktop was cleared"))));

    @Override
    public Optional<PcScenarioDefinition> findByName(String scenarioName) {
        if (scenarioName == null || scenarioName.isBlank()) {
            return Optional.empty();
        }
        String normalized = scenarioName.trim().toLowerCase(Locale.ROOT);
        return scenarios.stream()
                .filter(definition -> definition.name().equals(normalized))
                .findFirst();
    }

    @Override
    public List<PcScenarioDefinition> all() {
        return scenarios;
    }
}
