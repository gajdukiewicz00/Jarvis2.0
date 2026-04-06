package org.jarvis.voicegateway.voice;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves voice output using YAML routing rules.
 * Maps (action, locale) -> asset ID. If asset is unavailable, falls back to TTS.
 */
@Slf4j
@Component
public class RuleBasedVoiceOutputResolver implements VoiceOutputResolver {

    private static final String RULES_PATH = "voice-routing-rules.yaml";

    private List<Map<String, Object>> rules;
    private String defaultMode = "TTS";

    @PostConstruct
    public void loadRules() {
        try {
            ClassPathResource resource = new ClassPathResource(RULES_PATH);
            if (!resource.exists()) {
                log.warn("Voice routing rules not found: {}, all responses will use TTS", RULES_PATH);
                rules = List.of();
                return;
            }
            try (InputStream is = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                Map<String, Object> root = yaml.load(is);
                if (root == null) {
                    rules = List.of();
                    return;
                }
                defaultMode = root.containsKey("default") ? String.valueOf(root.get("default")) : "TTS";
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> r = (List<Map<String, Object>>) root.get("rules");
                rules = r != null ? r : List.of();
                log.info("Loaded {} voice routing rules, default={}", rules.size(), defaultMode);
            }
        } catch (Exception e) {
            log.error("Failed to load voice routing rules: {}", e.getMessage());
            rules = List.of();
        }
    }

    @Override
    public VoiceResponse resolve(String action, String text, String locale,
                                 String errorCode, Map<String, Object> params) {
        String assetId = lookupAssetId(action, locale);
        if (assetId != null) {
            return VoiceResponse.builder()
                    .mode(VoicePlaybackMode.PRE_RECORDED)
                    .text(text)
                    .audioAssetId(assetId)
                    .audioData(null)
                    .build();
        }
        if ("SILENT".equalsIgnoreCase(defaultMode)) {
            return VoiceResponse.silent(text);
        }
        return VoiceResponse.tts(text);
    }

    /**
     * Look up asset ID for (action, locale). Returns null if no rule or no asset for this locale.
     */
    public String lookupAssetId(String action, String locale) {
        if (action == null || action.isBlank()) {
            return null;
        }
        String loc = normalizeLocale(locale);

        for (Map<String, Object> rule : rules) {
            String ruleAction = (String) rule.get("action");
            if (action.equalsIgnoreCase(ruleAction)) {
                Object assetIdObj = rule.get(loc);
                if (assetIdObj != null && !assetIdObj.toString().isBlank()) {
                    return assetIdObj.toString();
                }
                return null;
            }
        }
        return null;
    }

    private static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return "ru";
        }
        return locale.toLowerCase(Locale.ROOT).startsWith("ru") ? "ru" : "en";
    }
}
