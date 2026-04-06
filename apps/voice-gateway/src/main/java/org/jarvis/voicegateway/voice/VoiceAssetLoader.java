package org.jarvis.voicegateway.voice;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads pre-recorded voice assets (.wav) by asset ID.
 * Assets are stored under classpath:voice-assets/{locale}/{category}/{id}.wav
 */
@Slf4j
@Component
public class VoiceAssetLoader {

    private static final String BASE_PATH = "voice-assets/";
    private static final String MANIFEST_PATH = BASE_PATH + "manifest.yaml";
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    private final Map<String, String> manifestResources = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadManifest();
        log.info("VoiceAssetLoader initialized, base path: {}, activeAssets={}", BASE_PATH, manifestResources.size());
    }

    /**
     * Load asset bytes by ID. Format: {locale}/{category}/{id}
     * e.g. ru/system/wake_yes_sir -> voice-assets/ru/system/wake_yes_sir.wav
     */
    public byte[] load(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            return null;
        }

        return cache.computeIfAbsent(assetId, this::loadFromClasspath);
    }

    /**
     * Check if an asset exists (loaded or loadable).
     */
    public boolean hasAsset(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            return false;
        }
        return load(assetId) != null;
    }

    private byte[] loadFromClasspath(String assetId) {
        String path = manifestResources.getOrDefault(assetId, BASE_PATH + assetId.replace(".wav", "") + ".wav");
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.debug("Voice asset not found: {}", path);
                return null;
            }
            try (InputStream is = resource.getInputStream()) {
                byte[] data = is.readAllBytes();
                log.info("Loaded voice asset: {} ({} bytes)", assetId, data.length);
                return data;
            }
        } catch (IOException e) {
            log.warn("Failed to load voice asset {}: {}", assetId, e.getMessage());
            return null;
        }
    }

    /** Pre-warm cache for given asset IDs (call at startup if needed). */
    public void prewarm(String... assetIds) {
        for (String id : assetIds) {
            load(id);
        }
    }

    public int getActiveAssetCount() {
        return manifestResources.size();
    }

    private void loadManifest() {
        try {
            ClassPathResource resource = new ClassPathResource(MANIFEST_PATH);
            if (!resource.exists()) {
                log.warn("Voice asset manifest not found: {}", MANIFEST_PATH);
                return;
            }
            try (InputStream is = resource.getInputStream()) {
                Object rootObject = new Yaml().load(is);
                if (!(rootObject instanceof Map<?, ?> root)) {
                    return;
                }
                Object assetsObject = root.get("assets");
                if (!(assetsObject instanceof List<?> assets)) {
                    return;
                }
                List<Map<?, ?>> orderedAssets = new ArrayList<>();
                for (Object rawAsset : assets) {
                    if (!(rawAsset instanceof Map<?, ?> asset)) {
                        continue;
                    }
                    orderedAssets.add(asset);
                }
                orderedAssets.sort(Comparator.comparing(asset -> stringValue(asset.get("id")) == null
                        ? ""
                        : stringValue(asset.get("id"))));
                for (Map<?, ?> asset : orderedAssets) {
                    String id = stringValue(asset.get("id"));
                    String status = stringValue(asset.get("status"));
                    if (id == null || (status != null && !"active".equalsIgnoreCase(status))) {
                        continue;
                    }
                    String resourcePath = stringValue(asset.get("resource"));
                    if (resourcePath == null) {
                        resourcePath = BASE_PATH + id + ".wav";
                    }
                    if (!resourcePath.startsWith(BASE_PATH)) {
                        log.warn("Ignoring voice asset {} with non-classpath resource '{}'", id, resourcePath);
                        continue;
                    }
                    if (manifestResources.putIfAbsent(id, resourcePath) != null) {
                        log.warn("Ignoring duplicate active voice asset id='{}' in {}", id, MANIFEST_PATH);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load voice asset manifest {}", MANIFEST_PATH, e);
        } catch (RuntimeException e) {
            log.warn("Failed to parse voice asset manifest {}", MANIFEST_PATH, e);
        }
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
