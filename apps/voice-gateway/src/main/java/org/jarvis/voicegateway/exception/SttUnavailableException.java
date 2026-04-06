package org.jarvis.voicegateway.exception;

/**
 * Thrown when STT is not configured or the active provider cannot fulfil a request.
 */
public class SttUnavailableException extends RuntimeException {
    public SttUnavailableException(String message) {
        super(message);
    }

    public SttUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
