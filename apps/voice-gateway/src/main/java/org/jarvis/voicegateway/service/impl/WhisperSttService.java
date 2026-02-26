package org.jarvis.voicegateway.service.impl;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.service.StreamingRecognitionSession;
import org.jarvis.voicegateway.service.SttService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Whisper-based STT service using whisper.cpp JNI bindings
 * Only active when jarvis.voice.whisper.enabled=true
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "jarvis.voice.whisper.enabled", havingValue = "true", matchIfMissing = false)
public class WhisperSttService implements SttService {

    @Value("${jarvis.voice.whisper.model-path:models/ggml-small.bin}")
    private String modelPath;

    @Value("${jarvis.voice.whisper.enabled:false}")
    private boolean enabled;

    private WhisperJNI whisper;
    private WhisperContext context;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("Whisper STT is disabled (jarvis.voice.whisper.enabled=false)");
            return;
        }

        try {
            WhisperJNI.loadLibrary();
            whisper = new WhisperJNI();

            Path path = Path.of(modelPath);
            if (!Files.exists(path)) {
                log.warn("Whisper model not found at '{}'. Whisper STT will not be available.", modelPath);
                log.info("To enable Whisper:");
                log.info("  1. Download model from: https://huggingface.co/ggerganov/whisper.cpp/tree/main");
                log.info("  2. Place it at: {}", path.toAbsolutePath());
                log.info("  3. Set jarvis.voice.whisper.enabled=true");
                return; // Don't throw - allow service to start without Whisper
            }

            context = whisper.init(path.toAbsolutePath());
            log.info("Whisper model loaded successfully from {}", path);
        } catch (IOException e) {
            log.error("Failed to initialize Whisper due to IO error: {}. Whisper STT will not be available.",
                    e.getMessage());
            // Don't throw - allow service to start
        } catch (RuntimeException e) {
            log.error("Failed to initialize Whisper: {}. Whisper STT will not be available.", e.getMessage());
            // Don't throw - allow service to start
        }
    }

    @Override
    public String transcribe(byte[] wav16kMonoPcm, String languageCode) {
        // If Whisper not initialized, return empty
        if (whisper == null || context == null) {
            log.warn("Whisper not initialized - cannot transcribe");
            return "";
        }

        // Convert byte[] (16-bit PCM) to float[]
        float[] samples = bytesToFloats(wav16kMonoPcm);

        try {
            WhisperFullParams params = new WhisperFullParams();
            params.language = languageCode != null ? languageCode.split("-")[0] : "ru";

            int result = whisper.full(context, params, samples, samples.length);
            if (result != 0) {
                log.error("Whisper transcription failed with code {}", result);
                return "";
            }

            int numSegments = whisper.fullNSegments(context);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < numSegments; i++) {
                text.append(whisper.fullGetSegmentText(context, i));
            }
            return text.toString().trim();

        } catch (RuntimeException e) {
            log.error("Transcription error", e);
            return "";
        }
    }

    @Override
    public StreamingRecognitionSession createSession() {
        return new WhisperSession();
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
        private long lastSpeechTime = System.currentTimeMillis();
        private boolean isSpeaking = false;
        private String lastResult = "";

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

            String text = transcribe(audio, "ru");
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
