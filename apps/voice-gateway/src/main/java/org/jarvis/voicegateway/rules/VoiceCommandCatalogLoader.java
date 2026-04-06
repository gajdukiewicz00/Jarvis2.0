package org.jarvis.voicegateway.rules;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class VoiceCommandCatalogLoader {

    private static final String RESOURCE_PATTERN = "classpath*:voice-commands/*.yaml";

    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
    private volatile List<VoiceCommandCatalog.Command> commands = List.of();

    @PostConstruct
    public void load() {
        List<VoiceCommandCatalog.Command> loaded = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        int duplicateCount = 0;

        try {
            Resource[] resources = resourceResolver.getResources(RESOURCE_PATTERN);
            List<Resource> orderedResources = new ArrayList<>(List.of(resources));
            orderedResources.sort(Comparator.comparing(VoiceCommandCatalogLoader::resourceSortKey));

            for (Resource resource : orderedResources) {
                for (VoiceCommandCatalog.Command command : loadCommands(resource)) {
                    if (!seenIds.add(command.id())) {
                        duplicateCount++;
                        log.warn("Ignoring duplicate rule command id='{}' from {}", command.id(), resource.getFilename());
                        continue;
                    }
                    loaded.add(command);
                }
            }

            commands = List.copyOf(loaded);
            log.info("Loaded {} rule-based voice commands from {}", commands.size(), RESOURCE_PATTERN);
            if (duplicateCount > 0) {
                log.warn("Dropped {} duplicate rule-based voice command ids", duplicateCount);
            }
        } catch (IOException e) {
            log.error("Failed to load rule-based voice commands from {}", RESOURCE_PATTERN, e);
            commands = List.of();
        }
    }

    public List<VoiceCommandCatalog.Command> commands() {
        return commands;
    }

    int getLoadedCommandCount() {
        return commands.size();
    }

    private List<VoiceCommandCatalog.Command> loadCommands(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            Object rootObject = new Yaml().load(inputStream);
            if (!(rootObject instanceof Map<?, ?> root)) {
                return List.of();
            }

            Object commandsObject = root.get("commands");
            if (!(commandsObject instanceof List<?> rawCommands)) {
                return List.of();
            }

            List<VoiceCommandCatalog.Command> loaded = new ArrayList<>();
            for (Object rawCommand : rawCommands) {
                if (!(rawCommand instanceof Map<?, ?> commandMap)) {
                    continue;
                }
                VoiceCommandCatalog.Command command = parseCommand(commandMap, resource.getFilename());
                if (command != null) {
                    loaded.add(command);
                }
            }
            log.debug("Loaded {} rule commands from {}", loaded.size(), resource.getFilename());
            return loaded;
        } catch (IOException e) {
            log.warn("Failed to read rule command catalog {}", resource.getFilename(), e);
            return List.of();
        } catch (RuntimeException e) {
            log.warn("Failed to parse rule command catalog {}", resource.getFilename(), e);
            return List.of();
        }
    }

    private VoiceCommandCatalog.Command parseCommand(Map<?, ?> commandMap, String resourceName) {
        String id = stringValue(commandMap.get("id"));
        String actionName = stringValue(commandMap.get("action"));
        Object actionObject = commandMap.get("action");

        VoiceCommandCatalog.Action action = parseAction(actionObject);
        if (action == null && actionName != null) {
            action = new VoiceCommandCatalog.Action(
                    VoiceCommandCatalog.ActionTarget.INTERNAL,
                    actionName,
                    null,
                    null,
                    Map.of());
        }

        List<VoiceCommandCatalog.Matcher> matchers = parseMatchers(commandMap.get("matchers"));
        if (matchers.isEmpty()) {
            log.warn("Ignoring rule command without matchers: id={}, resource={}", id, resourceName);
            return null;
        }
        if (id == null || id.isBlank()) {
            log.warn("Ignoring rule command without id in {}", resourceName);
            return null;
        }
        if (action == null || action.name() == null || action.name().isBlank()) {
            log.warn("Ignoring rule command without action: id={}, resource={}", id, resourceName);
            return null;
        }

        return new VoiceCommandCatalog.Command(
                id,
                stringValue(commandMap.get("description")),
                booleanValue(commandMap.get("enabled"), true),
                intValue(commandMap.get("priority"), 0),
                matchers,
                action,
                parseResponse(commandMap.get("response")));
    }

    private VoiceCommandCatalog.Action parseAction(Object rawAction) {
        if (rawAction instanceof String actionName) {
            return new VoiceCommandCatalog.Action(
                    VoiceCommandCatalog.ActionTarget.INTERNAL,
                    actionName.trim(),
                    null,
                    null,
                    Map.of());
        }
        if (!(rawAction instanceof Map<?, ?> actionMap)) {
            return null;
        }

        return new VoiceCommandCatalog.Action(
                VoiceCommandCatalog.ActionTarget.from(stringValue(actionMap.get("target"))),
                stringValue(actionMap.get("name")),
                stringValue(actionMap.get("deviceId")),
                actionMap.get("payload"),
                objectMap(actionMap.get("params")));
    }

    private VoiceCommandCatalog.Response parseResponse(Object rawResponse) {
        if (!(rawResponse instanceof Map<?, ?> responseMap)) {
            return null;
        }
        return new VoiceCommandCatalog.Response(
                stringValue(responseMap.get("key")),
                stringMap(responseMap.get("text")));
    }

    private List<VoiceCommandCatalog.Matcher> parseMatchers(Object rawMatchers) {
        if (!(rawMatchers instanceof List<?> matcherList)) {
            return List.of();
        }

        List<VoiceCommandCatalog.Matcher> parsed = new ArrayList<>();
        for (Object rawMatcher : matcherList) {
            if (!(rawMatcher instanceof Map<?, ?> matcherMap)) {
                continue;
            }
            List<String> values = stringList(matcherMap.get("values"));
            if (values.isEmpty()) {
                continue;
            }
            parsed.add(new VoiceCommandCatalog.Matcher(
                    VoiceCommandCatalog.MatcherType.from(stringValue(matcherMap.get("type"))),
                    values));
        }
        return List.copyOf(parsed);
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = stringValue(item);
            if (text != null) {
                result.add(text);
            }
        }
        return List.copyOf(result);
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
        return result;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = stringValue(entry.getKey());
            if (key != null && entry.getValue() != null) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static String resourceSortKey(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException e) {
            return resource.getDescription();
        }
    }
}
