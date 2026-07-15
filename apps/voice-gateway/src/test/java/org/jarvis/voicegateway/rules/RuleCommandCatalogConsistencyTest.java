package org.jarvis.voicegateway.rules;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleCommandCatalogConsistencyTest {

    @Test
    void runtimeRuleCatalogStaysConsistent() throws IOException {
        VoiceCommandCatalogLoader loader = new VoiceCommandCatalogLoader();
        loader.load();

        RuleBasedVoiceCommandService service = new RuleBasedVoiceCommandService(loader);
        service.init();

        List<Map<String, Object>> commands = loadCommandMaps();
        Map<String, Map<String, Object>> responsesByKey = loadResponsesByKey();

        assertTrue(loader.getLoadedCommandCount() >= 270, "Expected the difficult legacy remainder to be migrated");
        assertEquals(commands.size(), loader.getLoadedCommandCount(), "Raw YAML command count should match the loader count");

        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, String> exactAliasPhrases = new LinkedHashMap<>();
        Set<String> missingResponses = new LinkedHashSet<>();

        for (Map<String, Object> command : commands) {
            String id = stringValue(command.get("id"));
            assertFalse(id == null || id.isBlank(), "Command id must be present");
            assertTrue(ids.putIfAbsent(id, id) == null, "Duplicate command id: " + id);

            Object action = command.get("action");
            assertFalse(action == null, "Missing action for command " + id);

            Map<String, Object> response = objectMap(command.get("response"));
            String responseKey = stringValue(response.get("key"));
            // A command's spoken reply may come from a pre-recorded WAV (response.key), an inline
            // TTS text map (response.text), or be built DYNAMICALLY by the handler/gateway for
            // planner/finance summaries and internal intents (catalog, clarifications, status).
            boolean hasText = response.get("text") != null;
            String target = stringValue(objectMap(command.get("action")).get("target"));
            boolean dynamicTarget = target != null && switch (target.trim().toLowerCase()) {
                case "planner", "finance", "internal" -> true;
                default -> false;
            };
            if (responseKey != null && !responseKey.isBlank()) {
                assertTrue(responsesByKey.containsKey(responseKey),
                        "Command " + id + " references unknown response key " + responseKey);
            } else if (!hasText && !dynamicTarget) {
                missingResponses.add(id);
            }

            for (Map<String, Object> matcher : objectList(command.get("matchers"))) {
                String type = stringValue(matcher.get("type"));
                if (type == null) {
                    type = "contains";
                }
                String normalizedType = type.trim().toLowerCase();
                if (!normalizedType.equals("exact") && !normalizedType.equals("alias")) {
                    continue;
                }
                for (String value : stringList(matcher.get("values"))) {
                    String normalized = RuleBasedVoiceCommandService.normalize(value);
                    if (normalized.isBlank()) {
                        continue;
                    }
                    String existing = exactAliasPhrases.putIfAbsent(normalized, id);
                    assertTrue(existing == null || existing.equals(id),
                            "Phrase '" + normalized + "' is duplicated across commands " + existing + " and " + id);
                }
            }
        }

        assertTrue(missingResponses.isEmpty(), "Commands without response keys: " + missingResponses);
    }

    private static List<Map<String, Object>> loadCommandMaps() throws IOException {
        List<Map<String, Object>> commands = new ArrayList<>();
        for (Resource resource : commandResources()) {
            try (InputStream inputStream = resource.getInputStream()) {
                Object rootObject = new Yaml().load(inputStream);
                if (!(rootObject instanceof Map<?, ?> root)) {
                    continue;
                }
                Object commandsObject = root.get("commands");
                if (!(commandsObject instanceof List<?> list)) {
                    continue;
                }
                for (Object raw : list) {
                    if (raw instanceof Map<?, ?> command) {
                        commands.add(toStringObjectMap(command));
                    }
                }
            }
        }
        return commands;
    }

    private static Map<String, Map<String, Object>> loadResponsesByKey() throws IOException {
        try (InputStream inputStream = RuleCommandCatalogConsistencyTest.class.getClassLoader()
                .getResourceAsStream("voice-response-registry.yaml")) {
            Object rootObject = new Yaml().load(inputStream);
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            if (!(rootObject instanceof Map<?, ?> root)) {
                return result;
            }
            Object responsesObject = root.get("responses");
            if (!(responsesObject instanceof List<?> responses)) {
                return result;
            }
            for (Object raw : responses) {
                if (raw instanceof Map<?, ?> response) {
                    Map<String, Object> responseMap = toStringObjectMap(response);
                    String key = stringValue(responseMap.get("key"));
                    if (key != null) {
                        result.put(key, responseMap);
                    }
                }
            }
            return result;
        }
    }

    private static List<Resource> commandResources() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> resources = new ArrayList<>(List.of(resolver.getResources("classpath*:voice-commands/*.yaml")));
        resources.sort(Comparator.comparing(RuleCommandCatalogConsistencyTest::resourceSortKey));
        return resources;
    }

    private static List<Map<String, Object>> objectList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(toStringObjectMap(map));
            }
        }
        return result;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return toStringObjectMap(map);
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
        return result;
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = stringValue(entry.getKey());
            if (key != null) {
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
