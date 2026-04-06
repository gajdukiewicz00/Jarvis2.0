package org.jarvis.voicegateway.voice;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceAssetCatalogConsistencyTest {

    @Test
    void manifestAndRegistryStayInSync() throws IOException {
        VoiceAssetLoader assetLoader = new VoiceAssetLoader();
        assetLoader.init();

        Map<String, Map<String, Object>> activeAssets = loadActiveAssets();
        Set<String> registryAssetIds = loadRegistryAssetIds();

        assertTrue(activeAssets.size() >= 95, "Expected the full legacy WAV pack to be registered");
        assertTrue(registryAssetIds.size() >= 95, "Expected registry coverage for the legacy WAV pack");
        assertTrue(activeAssets.keySet().containsAll(registryAssetIds),
                "Registry references asset ids that are missing from the manifest");
        assertTrue(registryAssetIds.containsAll(activeAssets.keySet()),
                "Manifest contains active assets that are not reachable through response keys");

        for (String assetId : registryAssetIds) {
            assertTrue(assetLoader.hasAsset(assetId), "Voice asset bytes are missing for " + assetId);
        }
    }

    private static Map<String, Map<String, Object>> loadActiveAssets() throws IOException {
        try (InputStream inputStream = VoiceAssetCatalogConsistencyTest.class.getClassLoader()
                .getResourceAsStream("voice-assets/manifest.yaml")) {
            Object rootObject = new Yaml().load(inputStream);
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            if (!(rootObject instanceof Map<?, ?> root)) {
                return result;
            }
            Object assetsObject = root.get("assets");
            if (!(assetsObject instanceof List<?> assets)) {
                return result;
            }
            for (Object raw : assets) {
                if (!(raw instanceof Map<?, ?> asset)) {
                    continue;
                }
                Map<String, Object> assetMap = toStringObjectMap(asset);
                String status = stringValue(assetMap.get("status"));
                if (status != null && !"active".equalsIgnoreCase(status)) {
                    continue;
                }
                String id = stringValue(assetMap.get("id"));
                if (id != null) {
                    result.put(id, assetMap);
                }
            }
            return result;
        }
    }

    private static Set<String> loadRegistryAssetIds() throws IOException {
        try (InputStream inputStream = VoiceAssetCatalogConsistencyTest.class.getClassLoader()
                .getResourceAsStream("voice-response-registry.yaml")) {
            Object rootObject = new Yaml().load(inputStream);
            Set<String> result = new LinkedHashSet<>();
            if (!(rootObject instanceof Map<?, ?> root)) {
                return result;
            }
            Object responsesObject = root.get("responses");
            if (!(responsesObject instanceof List<?> responses)) {
                return result;
            }
            for (Object raw : responses) {
                if (!(raw instanceof Map<?, ?> response)) {
                    continue;
                }
                Map<String, Object> responseMap = toStringObjectMap(response);
                Map<String, Object> assets = objectMap(responseMap.get("assets"));
                for (Object assetId : assets.values()) {
                    String text = stringValue(assetId);
                    if (text != null) {
                        result.add(text);
                    }
                }
            }
            return result;
        }
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return toStringObjectMap(map);
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
}
