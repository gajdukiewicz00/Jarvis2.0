package org.jarvis.voicegateway.service.impl;

import lombok.extern.slf4j.Slf4j;
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

    @Value("${jarvis.vosk.model-path-ru:vosk-model-small-ru-0.22}")
    private String modelPathRu;

    @Value("${jarvis.vosk.model-path-en:vosk-model-small-en-us-0.15}")
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
        loadModelIfPresent(lang, pathForLanguage(lang));
    }

    @Override
    public String transcribe(byte[] wav16kMonoPcm, String languageCode) {
        String lang = normalizeLang(languageCode != null ? languageCode : defaultLanguage);
        Model model = getModelForLanguage(lang);
        if (model == null) {
            log.warn("No Vosk model available for lang={}, returning empty transcript", lang);
            return "";
        }

        try (Recognizer recognizer = new Recognizer(model, sampleRate)) {
            recognizer.acceptWaveForm(wav16kMonoPcm, wav16kMonoPcm.length);
            String resultJson = recognizer.getFinalResult();
            return extractText(resultJson);
        } catch (IOException e) {
            log.error("Vosk recognition failed for lang {}", lang, e);
            return "";
        }
    }

    @Override
    public StreamingRecognitionSession createSession(String languageCode) {
        String lang = normalizeLang(languageCode != null ? languageCode : defaultLanguage);
        Model model = getModelForLanguage(lang);
        if (model == null) {
            log.warn("No Vosk model available for lang={}, falling back to default {}", lang, defaultLanguage);
            model = getModelForLanguage(normalizeLang(defaultLanguage));
        }
        if (model == null) {
            log.error("No Vosk model loaded. Streaming session unavailable.");
            throw new IllegalStateException("Vosk STT model not loaded");
        }
        return new VoskSession(model, sampleRate, lang);
    }

    @Override
    public StreamingRecognitionSession createSession() {
        return createSession(defaultLanguage);
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
        if (lang == null || lang.isEmpty())
            return "ru";
        return lang.toLowerCase(Locale.ROOT);
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
