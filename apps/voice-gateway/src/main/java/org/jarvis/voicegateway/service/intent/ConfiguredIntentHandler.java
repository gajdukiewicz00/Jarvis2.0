package org.jarvis.voicegateway.service.intent;

import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.util.LanguageDetector;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads rule-based intents from resources/intents/*.yaml.
 * This activates legacy CSV/XML migrations without hard-coding every phrase in Java.
 */
@Slf4j
@Component
@Order(0)
public class ConfiguredIntentHandler implements IntentHandler {

    private static final String INTENT_RESOURCES = "classpath*:intents/*.yaml";

    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
    private volatile List<ConfiguredCommand> commands = List.of();

    @PostConstruct
    public void load() {
        List<ConfiguredCommand> loaded = new ArrayList<>();
        try {
            Resource[] resources = resourceResolver.getResources(INTENT_RESOURCES);
            List<Resource> orderedResources = new ArrayList<>(List.of(resources));
            orderedResources.sort(Comparator.comparing(ConfiguredIntentHandler::resourceSortKey));
            Set<String> seenIds = new LinkedHashSet<>();
            int duplicateCount = 0;
            for (Resource resource : orderedResources) {
                for (ConfiguredCommand command : loadCommands(resource)) {
                    if (!seenIds.add(command.id)) {
                        duplicateCount++;
                        log.warn("Ignoring duplicate configured intent id='{}' from {}", command.id,
                                resource.getFilename());
                        continue;
                    }
                    loaded.add(command);
                }
            }
            loaded.sort((left, right) -> Integer.compare(right.longestPhraseLength(), left.longestPhraseLength()));
            commands = List.copyOf(loaded);
            if (duplicateCount > 0) {
                log.warn("Dropped {} duplicate configured intent ids while loading {}", duplicateCount,
                        INTENT_RESOURCES);
            }
            log.info("Loaded {} configured voice intents from {}", commands.size(), INTENT_RESOURCES);
        } catch (IOException e) {
            log.error("Failed to load configured intents from {}", INTENT_RESOURCES, e);
            commands = List.of();
        }
    }

    @Override
    public boolean canHandle(IntentRequest request) {
        return request != null && request.getText() != null && !request.getText().isBlank();
    }

    @Override
    public IntentResult handle(IntentRequest request) {
        if (commands.isEmpty()) {
            return unknown(request);
        }

        String language = request.getLanguage();
        if (language == null || language.isBlank()) {
            language = LanguageDetector.detect(request.getText());
        }
        String normalizedText = normalize(request.getText());
        String boundedText = boundaryWrap(normalizedText);
        boolean english = language != null && language.toLowerCase(Locale.ROOT).startsWith("en");

        for (ConfiguredCommand command : commands) {
            for (String phrase : command.phrasesFor(english)) {
                if (matchesPhrase(boundedText, phrase)) {
                    log.info("✅ Matched configured intent: id={}, action={}, phrase='{}', correlationId={}",
                            command.id, command.action, phrase, request.getCorrelationId());
                    return IntentResult.builder()
                            .handled(true)
                            .action(command.action)
                            .correlationId(request.getCorrelationId())
                            .parameters(command.parameters)
                            .response(null)
                            .build();
                }
            }
        }

        return unknown(request);
    }

    public int getLoadedCommandsCount() {
        return commands.size();
    }

    private List<ConfiguredCommand> loadCommands(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            Object rootObject = new Yaml().load(inputStream);
            if (!(rootObject instanceof Map<?, ?> root)) {
                return List.of();
            }

            Object commandsObject = root.get("commands");
            if (!(commandsObject instanceof List<?> rawCommands)) {
                return List.of();
            }

            List<ConfiguredCommand> loaded = new ArrayList<>();
            for (Object rawCommand : rawCommands) {
                if (!(rawCommand instanceof Map<?, ?> commandMap)) {
                    continue;
                }
                String id = stringValue(commandMap.get("id"));
                String action = stringValue(commandMap.get("action"));
                if (id == null || action == null) {
                    continue;
                }

                ConfiguredCommand command = ConfiguredCommand.builder()
                        .id(id)
                        .action(action)
                        .phrasesRu(normalizeAll(stringList(commandMap.get("phrases_ru"))))
                        .phrasesEn(normalizeAll(stringList(commandMap.get("phrases_en"))))
                        .parameters(stringObjectMap(commandMap.get("params")))
                        .build();
                loaded.add(command);
            }

            log.debug("Loaded {} commands from {}", loaded.size(), resource.getFilename());
            return loaded;
        } catch (IOException e) {
            log.warn("Failed to read configured intents from {}", resource.getFilename(), e);
            return List.of();
        } catch (RuntimeException e) {
            log.warn("Failed to parse configured intents from {}", resource.getFilename(), e);
            return List.of();
        }
    }

    private IntentResult unknown(IntentRequest request) {
        return IntentResult.builder()
                .handled(false)
                .action("UNKNOWN")
                .correlationId(request != null ? request.getCorrelationId() : null)
                .parameters(Map.of())
                .response(null)
                .build();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private static Map<String, Object> stringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static List<String> normalizeAll(List<String> phrases) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String phrase : phrases) {
            String value = normalize(phrase);
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private static String resourceSortKey(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException e) {
            return resource.getDescription();
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[\\p{Punct}&&[^:/]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean matchesPhrase(String boundedText, String phrase) {
        return boundedText.contains(boundaryWrap(phrase));
    }

    private static String boundaryWrap(String value) {
        return " " + value + " ";
    }

    @Value
    @Builder
    private static class ConfiguredCommand {
        String id;
        String action;
        @Builder.Default
        List<String> phrasesRu = List.of();
        @Builder.Default
        List<String> phrasesEn = List.of();
        @Builder.Default
        Map<String, Object> parameters = Map.of();

        List<String> phrasesFor(boolean english) {
            List<String> selected = english ? phrasesEn : phrasesRu;
            if (!selected.isEmpty()) {
                return selected;
            }
            return english ? phrasesRu : phrasesEn;
        }

        int longestPhraseLength() {
            int ru = phrasesRu.stream().mapToInt(String::length).max().orElse(0);
            int en = phrasesEn.stream().mapToInt(String::length).max().orElse(0);
            return Math.max(ru, en);
        }
    }
}
