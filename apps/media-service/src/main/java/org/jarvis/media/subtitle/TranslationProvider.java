package org.jarvis.media.subtitle;

/**
 * Translates a single line of (already guard-neutralized) text into the target
 * language. Implementations may be LLM-backed; the default is a deterministic mock.
 * Callers MUST neutralize untrusted text via {@link MediaTextGuard} before calling.
 */
public interface TranslationProvider {

    String translate(String neutralizedText, String targetLanguage);
}
