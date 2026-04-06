package org.jarvis.voicegateway.service.impl;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.exception.SttUnavailableException;
import org.jarvis.voicegateway.service.StreamingRecognitionSession;
import org.jarvis.voicegateway.service.SttService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Whisper-based STT service using whisper.cpp JNI bindings.
 * Active when jarvis.stt.provider=whisper.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "jarvis.stt.provider", havingValue = "whisper")
public class WhisperSttService implements SttService {

    @Value("${jarvis.voice.whisper.model-path:${JARVIS_MODELS_DIR:models}/stt/whisper/ggml-small.bin}")
    private String modelPath;

    private WhisperJNI whisper;
    private WhisperContext context;
    private volatile String initStatus = "Whisper STT not initialized";

    @PostConstruct
    public void init() {
        try {
            WhisperJNI.loadLibrary();
            whisper = new WhisperJNI();

            Path path = Path.of(modelPath);
            if (!Files.exists(path)) {
                initStatus = "Whisper model not found at " + path.toAbsolutePath();
                log.warn("Whisper model not found at '{}'. Whisper STT will not be available.", modelPath);
                log.info("To enable Whisper:");
                log.info("  1. Download model from: https://huggingface.co/ggerganov/whisper.cpp/tree/main");
                log.info("  2. Place it at: {}", path.toAbsolutePath());
                log.info("  3. Set jarvis.stt.provider=whisper");
                return; // Don't throw - allow service to start without Whisper
            }

            context = whisper.init(path.toAbsolutePath());
            initStatus = "ready";
            log.info("Whisper model loaded successfully from {}", path);
        } catch (IOException e) {
            initStatus = "Failed to initialize Whisper due to IO error: " + e.getMessage();
            log.error("Failed to initialize Whisper due to IO error: {}. Whisper STT will not be available.",
                    e.getMessage());
            // Don't throw - allow service to start
        } catch (RuntimeException e) {
            initStatus = "Failed to initialize Whisper: " + e.getMessage();
            log.error("Failed to initialize Whisper: {}. Whisper STT will not be available.", e.getMessage());
            // Don't throw - allow service to start
        }
    }

    @Override
    public String transcribe(byte[] wav16kMonoPcm, String languageCode) {
        ensureAvailable();

        // Convert byte[] (16-bit PCM) to float[]
        float[] samples = bytesToFloats(wav16kMonoPcm);

        try {
            WhisperFullParams params = new WhisperFullParams();
            params.language = languageCode != null ? languageCode.split("-")[0] : "ru";

            int result = whisper.full(context, params, samples, samples.length);
            if (result != 0) {
                throw new SttUnavailableException("Whisper transcription failed with code " + result);
            }

            int numSegments = whisper.fullNSegments(context);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < numSegments; i++) {
                text.append(whisper.fullGetSegmentText(context, i));
            }
            return text.toString().trim();

        } catch (RuntimeException e) {
            log.error("Transcription error", e);
            throw new SttUnavailableException("Whisper transcription failed: " + e.getMessage(), e);
        }
    }

    @Override
    public StreamingRecognitionSession createSession(String languageCode) {
        ensureAvailable();
        String requestedLanguage = languageCode != null ? languageCode : "ru-RU";
        String lang = requestedLanguage.split("-")[0].toLowerCase();
        log.info("Creating Whisper streaming session: requestedLanguage={}, effectiveLanguage={}",
                requestedLanguage, lang);
        return new WhisperSession(lang);
    }

    @Override
    public StreamingRecognitionSession createSession() {
        return createSession("ru");
    }

    @Override
    public String providerId() {
        return "whisper";
    }

    @Override
    public Map<String, Object> describeRuntime() {
        boolean pathExists = Files.exists(Path.of(modelPath));
        boolean available = whisper != null && context != null;

        return Map.of(
                "configuredProvider", providerId(),
                "status", available ? "available" : "unavailable",
                "available", available,
                "modelPath", Path.of(modelPath).toAbsolutePath().toString(),
                "pathExists", pathExists,
                "reason", available ? "ready" : initStatus);
    }

    private void ensureAvailable() {
        if (whisper != null && context != null) {
            return;
        }
        throw new SttUnavailableException(initStatus);
    }

    private float[] bytesToFloats(byte[] bytes) {
        float[] floats = new float[bytes.length / 2];
        for (int i = 0; i < floats.length; i++) {
            int val = ((bytes[2 * i + 1] & 0xFF) << 8) | (bytes[2 * i] & 0xFF);
            if (val > 32767)
                val -= 65536;
            floats[i] = val / 32768.0f;
        }
        return floats;
    }

    private class WhisperSession implements StreamingRecognitionSession {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final String language;
        private long lastSpeechTime = System.currentTimeMillis();
        private boolean isSpeaking = false;
        private String lastResult = "";

        WhisperSession(String language) {
            this.language = language;
        }

        @Override
        public boolean acceptWaveForm(byte[] data, int length) {
            buffer.write(data, 0, length);

            // Simple energy-based VAD
            double energy = calculateEnergy(data, length);
            if (energy > 0.01) { // Threshold
                lastSpeechTime = System.currentTimeMillis();
                isSpeaking = true;
                return false;
            } else {
                // Silence detected
                if (isSpeaking && (System.currentTimeMillis() - lastSpeechTime > 500)) { // 500ms silence
                    isSpeaking = false;
                    return true; // Trigger final result
                }
            }
            return false;
        }

        @Override
        public String getPartialResult() {
            // Whisper doesn't support real-time partials easily without re-running on
            // buffer
            // For MVP, we only return final result on silence
            return "";
        }

        @Override
        public String getResult() {
            byte[] audio = buffer.toByteArray();
            buffer.reset();

            if (audio.length == 0)
                return "";

            String text = transcribe(audio, language);
            lastResult = text;
            return "{\"text\": \"" + text.replace("\"", "\\\"") + "\"}";
        }

        @Override
        public void close() {
            try {
                buffer.close();
            } catch (IOException e) {
                // ignore
            }
        }

        private double calculateEnergy(byte[] data, int length) {
            long sum = 0;
            for (int i = 0; i < length; i += 2) {
                int val = ((data[i + 1] & 0xFF) << 8) | (data[i] & 0xFF);
                if (val > 32767)
                    val -= 65536;
                sum += val * val;
            }
            return Math.sqrt(sum / (length / 2.0)) / 32768.0;
        }
    }
}
