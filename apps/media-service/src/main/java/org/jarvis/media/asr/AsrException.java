package org.jarvis.media.asr;

/** Thrown when automatic speech recognition fails. Surfaces as a FAILED job. */
public class AsrException extends RuntimeException {
    public AsrException(String message) {
        super(message);
    }
}
