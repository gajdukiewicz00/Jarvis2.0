package org.jarvis.voicegateway.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.exception.SttUnavailableException;
import org.jarvis.voicegateway.service.StreamingRecognitionSession;
import org.jarvis.voicegateway.service.SttService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.vosk.Model;
import org.vosk.Recognizer;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vosk-based STT service with support for Russian and English models.
 * Uses 16 kHz mono PCM input (same as desktop client sends).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "jarvis.stt.provider", havingValue = "vosk", matchIfMissing = true)
public class VoskSttService implements SttService {

    @Value("${jarvis.vosk.model-path-ru:${JARVIS_MODELS_DIR:models}/stt/vosk/vosk-model-small-ru-0.22}")
    private String modelPathRu;

    @Value("${jarvis.vosk.model-path-en:${JARVIS_MODELS_DIR:models}/stt/vosk/vosk-model-small-en-us-0.15}")
    private String modelPathEn;

    @Value("${jarvis.vosk.default-language:ru-RU}")
    private String defaultLanguage;

    @Value("${jarvis.vosk.sample-rate:16000}")
    private float sampleRate;

    private final Map<String, Model> models = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Load default language model eagerly if exists
        String lang = normalizeLang(defaultLanguage);
        Path defaultPath = pathForLanguage(lang);
        log.info("Initializing Vosk STT: defaultLanguage={}, effectiveDefaultLanguage={}, modelPath={}",
                defaultLanguage, lang, describePath(defaultPath));
        loadModelIfPresent(lang, defaultPath);
    }

    @Override
    public String transcribe(byte[] wav16kMonoPcm, String languageCode) {
        String requestedLanguage = languageCode != null ? languageCode : defaultLanguage;
        String lang = normalizeLang(requestedLanguage);
        Model model = getModelForLanguage(lang);
        if (model == null) {
            throw unavailableForLanguage(requestedLanguage, lang, pathForLanguage(lang));
        }

        try (Recognizer recognizer = new Recognizer(model, sampleRate)) {
            recognizer.acceptWaveForm(wav16kMonoPcm, wav16kMonoPcm.length);
            String resultJson = recognizer.getFinalResult();
            return extractText(resultJson);
        } catch (IOException e) {
            log.error("Vosk recognition failed for lang {}", lang, e);
            throw new SttUnavailableException("Vosk recognition failed for language " + requestedLanguage, e);
        }
    }

    @Override
    public StreamingRecognitionSession createSession(String languageCode) {
        String requestedLanguage = languageCode != null ? languageCode : defaultLanguage;
        String lang = normalizeLang(requestedLanguage);
        Path modelPath = pathForLanguage(lang);
        Model model = getModelForLanguage(lang);
        if (model == null) {
            throw unavailableForLanguage(requestedLanguage, lang, modelPath);
        }
        log.info("Creating Vosk streaming session: requestedLanguage={}, effectiveLanguage={}, modelPath={}, sampleRate={}",
                requestedLanguage, lang, describePath(modelPath), sampleRate);
        return new VoskSession(model, sampleRate, lang);
    }

    @Override
    public StreamingRecognitionSession createSession() {
        return createSession(defaultLanguage);
    }

    @Override
    public String providerId() {
        return "vosk";
    }

    @Override
    public Map<String, Object> describeRuntime() {
        Map<String, Object> languages = new LinkedHashMap<>();
        languages.put("ru-RU", describeLanguage("ru-ru"));
        languages.put("en-US", describeLanguage("en-us"));

        boolean ruAvailable = Boolean.TRUE.equals(((Map<?, ?>) languages.get("ru-RU")).get("available"));
        boolean enAvailable = Boolean.TRUE.equals(((Map<?, ?>) languages.get("en-US")).get("available"));
        boolean defaultAvailable = isLanguageAvailable(normalizeLang(defaultLanguage));

        String status;
        if (!defaultAvailable) {
            status = "unavailable";
        } else if (ruAvailable && enAvailable) {
            status = "available";
        } else {
            status = "partial";
        }

        return Map.of(
                "configuredProvider", providerId(),
                "status", status,
                "available", defaultAvailable,
                "defaultLanguage", canonicalLanguage(defaultLanguage),
                "sampleRate", sampleRate,
                "languages", Map.copyOf(languages));
    }

    private Model getModelForLanguage(String lang) {
        return models.computeIfAbsent(lang, l -> loadModelIfPresent(l, pathForLanguage(l)));
    }

    private Model loadModelIfPresent(String lang, Path path) {
        if (path == null)
            return null;
        if (!Files.exists(path)) {
            log.warn("Vosk model for {} not found at {}", lang, path.toAbsolutePath());
            return null;
        }
        try {
            log.info("Loading Vosk model for {} from {}", lang, path.toAbsolutePath());
            return new Model(path.toString());
        } catch (IOException e) {
            log.error("Failed to load Vosk model for {} at {}: IO error", lang, path, e);
            return null;
        } catch (RuntimeException e) {
            log.error("Failed to load Vosk model for {} at {}", lang, path, e);
            return null;
        }
    }

    private Path pathForLanguage(String lang) {
        String normalized = normalizeLang(lang);
        if (normalized.startsWith("ru")) {
            return Path.of(modelPathRu);
        }
        if (normalized.startsWith("en")) {
            return Path.of(modelPathEn);
        }
        return Path.of(modelPathRu); // fallback
    }

    private String normalizeLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return "ru-ru";
        }
        String normalized = lang.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        return normalized.startsWith("en") ? "en-us" : "ru-ru";
    }

    private String extractText(String resultJson) {
        if (resultJson == null)
            return "";
        // resultJson format: {"text" : "hello world"}
        int idx = resultJson.indexOf("\"text\"");
        if (idx < 0)
            return resultJson.trim();
        int colon = resultJson.indexOf(':', idx);
        int quoteStart = resultJson.indexOf('"', colon + 1);
        int quoteEnd = resultJson.indexOf('"', quoteStart + 1);
        if (quoteStart >= 0 && quoteEnd > quoteStart) {
            return resultJson.substring(quoteStart + 1, quoteEnd).trim();
        }
        return resultJson.trim();
    }

    private String describePath(Path path) {
        return path != null ? path.toAbsolutePath().toString() : "<none>";
    }

    private boolean isLanguageAvailable(String lang) {
        return getModelForLanguage(lang) != null;
    }

    private Map<String, Object> describeLanguage(String lang) {
        Path path = pathForLanguage(lang);
        boolean pathExists = path != null && Files.exists(path);
        boolean modelLoaded = getModelForLanguage(lang) != null;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("available", modelLoaded);
        details.put("modelPath", describePath(path));
        details.put("pathExists", pathExists);
        details.put("loaded", modelLoaded);
        if (!modelLoaded) {
            details.put("reason", "Model is missing or failed to load");
        }
        return Map.copyOf(details);
    }

    private SttUnavailableException unavailableForLanguage(String requestedLanguage, String effectiveLanguage, Path modelPath) {
        String message = String.format(
                "Vosk STT model for language %s is unavailable (effective=%s, path=%s)",
                requestedLanguage,
                canonicalLanguage(effectiveLanguage),
                describePath(modelPath));
        log.warn(message);
        return new SttUnavailableException(message);
    }

    private String canonicalLanguage(String lang) {
        return normalizeLang(lang).startsWith("en") ? "en-US" : "ru-RU";
    }

    private static class VoskSession implements StreamingRecognitionSession {
        private final Recognizer recognizer;
        private final String language;

        VoskSession(Model model, float sampleRate, String language) {
            this.language = language;
            try {
                this.recognizer = new Recognizer(model, sampleRate);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create Vosk recognizer for lang " + language, e);
            }
        }

        @Override
        public boolean acceptWaveForm(byte[] data, int length) {
            return recognizer.acceptWaveForm(data, length);
        }

        @Override
        public String getPartialResult() {
            return recognizer.getPartialResult();
        }

        @Override
        public String getResult() {
            return recognizer.getFinalResult();
        }

        @Override
        public void close() {
            recognizer.close();
        }
    }
}
