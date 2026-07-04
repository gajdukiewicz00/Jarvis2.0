package org.jarvis.media.tts;

/** Thrown when text-to-speech synthesis fails for a segment. */
public class TtsException extends RuntimeException {
    public TtsException(String message) {
        super(message);
    }
}
