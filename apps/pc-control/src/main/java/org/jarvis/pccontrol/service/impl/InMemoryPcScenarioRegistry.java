package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.model.PcScenarioDefinition;
import org.jarvis.pccontrol.model.PcScenarioStep;
import org.jarvis.pccontrol.service.PcScenarioRegistry;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class InMemoryPcScenarioRegistry implements PcScenarioRegistry {

    private static final String RESOURCE = "pc-scenarios.yaml";

    private final List<PcScenarioDefinition> scenarios = loadScenarios();

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

    private List<PcScenarioDefinition> loadScenarios() {
        List<PcScenarioDefinition> defaults = defaultScenarios();
        List<PcScenarioDefinition> loaded = new ArrayList<>(defaults);
        Set<String> seenNames = new LinkedHashSet<>();
        for (PcScenarioDefinition definition : defaults) {
            seenNames.add(definition.name());
        }
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (inputStream == null) {
                log.warn("Scenario catalog {} was not found", RESOURCE);
                return List.copyOf(loaded);
            }
            Object rootObject = new Yaml().load(inputStream);
            if (!(rootObject instanceof Map<?, ?> root)) {
                return List.copyOf(loaded);
            }
            Object scenariosObject = root.get("scenarios");
            if (!(scenariosObject instanceof List<?> rawScenarios)) {
                return List.copyOf(loaded);
            }

            for (Object rawScenario : rawScenarios) {
                if (!(rawScenario instanceof Map<?, ?> scenarioMap)) {
                    continue;
                }
                String name = stringValue(scenarioMap.get("name"));
                if (name == null) {
                    continue;
                }
                String normalizedName = name.trim().toLowerCase(Locale.ROOT);
                if (!seenNames.add(normalizedName)) {
                    log.warn("Skipping duplicate pc-control scenario '{}'", normalizedName);
                    continue;
                }
                List<PcScenarioStep> steps = new ArrayList<>();
                Object stepsObject = scenarioMap.get("steps");
                if (stepsObject instanceof List<?> rawSteps) {
                    for (Object rawStep : rawSteps) {
                        if (!(rawStep instanceof Map<?, ?> stepMap)) {
                            continue;
                        }
                        steps.add(new PcScenarioStep(
                                stringValue(stepMap.get("id")),
                                stringValue(stepMap.get("actionType")),
                                stringMap(stepMap.get("parameters")),
                                stringValue(stepMap.get("description"))));
                    }
                }
                loaded.add(new PcScenarioDefinition(
                        normalizedName,
                        stringValue(scenarioMap.get("description")),
                        steps));
            }
            log.info("Loaded {} pc-control scenarios ({} defaults, {} yaml extensions) from {}",
                    loaded.size(), defaults.size(), loaded.size() - defaults.size(), RESOURCE);
            return List.copyOf(loaded);
        } catch (Exception e) {
            log.warn("Failed to load pc-control scenarios from {}", RESOURCE, e);
            return List.copyOf(loaded);
        }
    }

    private static List<PcScenarioDefinition> defaultScenarios() {
        return List.of(
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
                                        "Notify that the desktop was cleared"))),
                new PcScenarioDefinition(
                        "cozy_evening",
                        "Low volume ambient setup for a calmer desktop session.",
                        List.of(
                                new PcScenarioStep("resume-media", "PLAY_PAUSE", Map.of(), "Toggle playback"),
                                new PcScenarioStep("set-volume", "SET_VOLUME", Map.of("level", "25"), "Lower volume"),
                                new PcScenarioStep("notify", "NOTIFY",
                                        Map.of("title", "Cozy Evening", "message", "Cozy evening protocol activated"),
                                        "Notify that cozy evening is active"))),
                new PcScenarioDefinition(
                        "guests",
                        "Prepare the desktop for guests with moderate volume and a notification.",
                        List.of(
                                new PcScenarioStep("resume-media", "PLAY_PAUSE", Map.of(), "Toggle playback"),
                                new PcScenarioStep("set-volume", "SET_VOLUME", Map.of("level", "45"), "Set guest volume"),
                                new PcScenarioStep("notify", "NOTIFY",
                                        Map.of("title", "Guests", "message", "Guest protocol activated"),
                                        "Notify that guest mode is active"))),
                new PcScenarioDefinition(
                        "holiday",
                        "Raise volume slightly for holiday music.",
                        List.of(
                                new PcScenarioStep("resume-media", "PLAY_PAUSE", Map.of(), "Toggle playback"),
                                new PcScenarioStep("set-volume", "SET_VOLUME", Map.of("level", "55"), "Set holiday volume"),
                                new PcScenarioStep("notify", "NOTIFY",
                                        Map.of("title", "Holiday", "message", "Holiday protocol activated"),
                                        "Notify that holiday mode is active"))),
                new PcScenarioDefinition(
                        "game",
                        "Prepare the desktop for gaming.",
                        List.of(
                                new PcScenarioStep("set-volume", "SET_VOLUME", Map.of("level", "60"), "Raise volume"),
                                new PcScenarioStep("notify", "NOTIFY",
                                        Map.of("title", "Game Mode", "message", "Game mode activated"),
                                        "Notify that game mode is active"))),
                new PcScenarioDefinition(
                        "morning",
                        "Start the day with browser access and moderate volume.",
                        List.of(
                                new PcScenarioStep("open-browser", "OPEN_APP", Map.of("app", "browser"), "Open browser"),
                                new PcScenarioStep("set-volume", "SET_VOLUME", Map.of("level", "35"), "Set morning volume"),
                                new PcScenarioStep("notify", "NOTIFY",
                                        Map.of("title", "Morning", "message", "Morning protocol activated"),
                                        "Notify that morning mode is active"))),
                new PcScenarioDefinition(
                        "leaving",
                        "Pause playback and clear the desktop before leaving.",
                        List.of(
                                new PcScenarioStep("pause-media", "PAUSE", Map.of(), "Pause playback"),
                                new PcScenarioStep("show-desktop", "HOTKEY", Map.of("keyCombination", "Super+d"),
                                        "Show desktop"),
                                new PcScenarioStep("notify", "NOTIFY",
                                        Map.of("title", "Leaving", "message", "Leaving protocol activated"),
                                        "Notify that leaving mode is active"))),
                new PcScenarioDefinition(
                        "panic",
                        "Minimize distractions quickly.",
                        List.of(
                                new PcScenarioStep("mute", "MUTE", Map.of(), "Mute audio"),
                                new PcScenarioStep("show-desktop", "HOTKEY", Map.of("keyCombination", "Super+d"),
                                        "Show desktop"),
                                new PcScenarioStep("notify", "NOTIFY",
                                        Map.of("title", "Panic", "message", "Panic protocol activated"),
                                        "Notify that panic mode is active"))));
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = stringValue(entry.getKey());
            String text = stringValue(entry.getValue());
            if (key != null && text != null) {
                result.put(key, text);
            }
        }
        return Map.copyOf(result);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
