package org.jarvis.media.subtitle;

/** Thrown when LLM-backed translation fails. Surfaces as a FAILED job (async only). */
public class TranslationException extends RuntimeException {

    public TranslationException(String message) {
        super(message);
    }

    public TranslationException(String message, Throwable cause) {
        super(message, cause);
    }
}
