package org.jarvis.voicegateway.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.service.StreamingRecognitionSession;
import org.jarvis.voicegateway.service.SttService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * No-op STT service that acts as a fallback when no real STT backend is configured.
 * 
 * IMPORTANT: This service does NOT pretend to work. Instead, it throws an exception
 * to indicate that STT is not configured. This prevents "silent failures" where
 * the user thinks transcription happened but got empty results.
 * 
 * To enable real STT:
 * - Set jarvis.voice.whisper.enabled=true and configure whisper model path
 * - Or configure another STT provider
 */
@Slf4j
@Service
@ConditionalOnExpression("!${jarvis.vosk.enabled:true} and !${jarvis.voice.whisper.enabled:false}")
public class NoOpSttService implements SttService {

    private static final String STT_UNAVAILABLE_MESSAGE = 
            "Speech-to-Text is not configured. Enable Vosk (jarvis.vosk.enabled=true) or Whisper.";

    public NoOpSttService() {
        log.warn("⚠️ No STT service configured! STT requests will fail with honest error.");
        log.warn("To enable STT:");
        log.warn("  - Preferred: jarvis.vosk.enabled=true and provide Vosk model paths");
        log.warn("  - Or enable Whisper: jarvis.voice.whisper.enabled=true and set jarvis.voice.whisper.model-path");
    }

    /**
     * Indicates if STT is available.
     * NoOpSttService always returns false.
     */
    public boolean isAvailable() {
        return false;
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

    /**
     * Exception thrown when STT is not configured.
     */
    public static class SttUnavailableException extends RuntimeException {
        public SttUnavailableException(String message) {
            super(message);
        }
    }
}

