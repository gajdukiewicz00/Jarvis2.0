package org.jarvis.voicegateway.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.exception.SttUnavailableException;
import org.jarvis.voicegateway.service.StreamingRecognitionSession;
import org.jarvis.voicegateway.service.SttService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * No-op STT service that acts as a fallback when no real STT backend is configured.
 *
 * Active when jarvis.stt.provider=noop.
 *
 * This service does NOT pretend to work. Instead, it throws
 * {@link SttUnavailableException} to prevent silent failures.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "jarvis.stt.provider", havingValue = "noop")
public class NoOpSttService implements SttService {

    private static final String STT_UNAVAILABLE_MESSAGE =
            "Speech-to-Text is not configured. Set jarvis.stt.provider to vosk or whisper.";

    public NoOpSttService() {
        log.warn("No STT provider configured. STT requests will fail with an honest error.");
        log.warn("To enable STT set jarvis.stt.provider=vosk (or whisper)");
    }

    public boolean isAvailable() {
        return false;
    }

    @Override
    public String providerId() {
        return "noop";
    }

    @Override
    public Map<String, Object> describeRuntime() {
        return Map.of(
                "configuredProvider", providerId(),
                "status", "disabled",
                "available", false,
                "reason", STT_UNAVAILABLE_MESSAGE);
    }

    @Override
    public String transcribe(byte[] wav16kMonoPcm, String languageCode) {
        log.warn("STT transcribe called but no STT backend configured!");
        throw new SttUnavailableException(STT_UNAVAILABLE_MESSAGE);
    }

    @Override
    public StreamingRecognitionSession createSession() {
        log.warn("STT streaming session requested but no STT backend configured!");
        throw new SttUnavailableException(STT_UNAVAILABLE_MESSAGE);
    }
}
