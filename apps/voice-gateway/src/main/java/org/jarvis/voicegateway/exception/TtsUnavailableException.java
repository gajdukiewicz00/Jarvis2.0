package org.jarvis.voicegateway.exception;

/**
 * Thrown when the configured TTS provider is unavailable or cannot synthesize audio.
 */
public class TtsUnavailableException extends RuntimeException {
    public TtsUnavailableException(String message) {
        super(message);
    }

    public TtsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
