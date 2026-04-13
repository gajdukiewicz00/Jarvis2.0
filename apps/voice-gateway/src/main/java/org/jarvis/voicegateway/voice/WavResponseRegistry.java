package org.jarvis.voicegateway.voice;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.rules.VoiceCommandCatalog;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class WavResponseRegistry {

    private static final String REGISTRY_PATH = "voice-response-registry.yaml";

    private volatile Map<String, ResponseProfile> profiles = Map.of();

    @PostConstruct
    public void load() {
        try {
            ClassPathResource resource = new ClassPathResource(REGISTRY_PATH);
            if (!resource.exists()) {
                log.warn("WAV response registry not found: {}", REGISTRY_PATH);
                profiles = Map.of();
                return;
            }

            try (InputStream is = resource.getInputStream()) {
                Object rootObject = new Yaml().load(is);
                if (!(rootObject instanceof Map<?, ?> root)) {
                    profiles = Map.of();
                    return;
                }
                Object responsesObject = root.get("responses");
                if (!(responsesObject instanceof List<?> responses)) {
                    profiles = Map.of();
                    return;
                }

                Map<String, ResponseProfile> loaded = new LinkedHashMap<>();
                List<Map<?, ?>> orderedResponses = new ArrayList<>();
                for (Object rawResponse : responses) {
                    if (rawResponse instanceof Map<?, ?> responseMap) {
                        orderedResponses.add(responseMap);
                    }
                }
                orderedResponses.sort(Comparator.comparing(response -> stringValue(response.get("key")) == null
                        ? ""
                        : stringValue(response.get("key"))));

                for (Map<?, ?> response : orderedResponses) {
                    String key = stringValue(response.get("key"));
                    if (key == null) {
                        continue;
                    }
                    ResponseProfile profile = new ResponseProfile(
                            key,
                            stringMap(response.get("assets")),
                            stringMap(response.get("text")));
                    if (loaded.putIfAbsent(key, profile) != null) {
                        log.warn("Ignoring duplicate WAV response key='{}' in {}", key, REGISTRY_PATH);
                    }
                }
                profiles = Map.copyOf(loaded);
                log.info("Loaded {} WAV response profiles from {}", profiles.size(), REGISTRY_PATH);
            }
        } catch (IOException e) {
            log.warn("Failed to load WAV response registry {}", REGISTRY_PATH, e);
            profiles = Map.of();
        } catch (RuntimeException e) {
            log.warn("Failed to parse WAV response registry {}", REGISTRY_PATH, e);
            profiles = Map.of();
        }
    }

    public String lookupAssetId(String responseKey, String locale) {
        ResponseProfile profile = profile(responseKey);
        return profile != null ? profile.assetFor(locale) : null;
    }

    public String lookupText(String responseKey, String locale) {
        ResponseProfile profile = profile(responseKey);
        return profile != null ? profile.textFor(locale) : null;
    }

    public int getLoadedProfileCount() {
        return profiles.size();
    }

    public Set<String> getReferencedAssetIds() {
        LinkedHashSet<String> assetIds = new LinkedHashSet<>();
        profiles.values().forEach(profile -> assetIds.addAll(profile.assets().values()));
        assetIds.removeIf(assetId -> assetId == null || assetId.isBlank());
        return Set.copyOf(assetIds);
    }

    private ResponseProfile profile(String responseKey) {
        if (responseKey == null || responseKey.isBlank()) {
            return null;
        }
        return profiles.get(responseKey);
    }

    public record ResponseProfile(String key, Map<String, String> assets, Map<String, String> text) {

        public ResponseProfile {
            assets = assets == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(assets));
            text = text == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(text));
        }

        public String assetFor(String locale) {
            return localizedValue(assets, locale);
        }

        public String textFor(String locale) {
            return localizedValue(text, locale);
        }

        private String localizedValue(Map<String, String> values, String locale) {
            if (values.isEmpty()) {
                return null;
            }
            String normalizedLocale = VoiceCommandCatalog.normalizeLocale(locale);
            String localized = values.get(normalizedLocale);
            if (localized != null && !localized.isBlank()) {
                return localized;
            }
            localized = values.get("ru");
            if (localized != null && !localized.isBlank()) {
                return localized;
            }
            localized = values.get("en");
            if (localized != null && !localized.isBlank()) {
                return localized;
            }
            return values.values().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(null);
        }
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

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
