package org.jarvis.media.subtitle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic translation mock (the default). Echoes the already-neutralized input
 * with a {@code [RU]} marker. Because the caller neutralizes injection markers first,
 * any "ignore previous instructions"-style text arrives here already defanged — the
 * provider treats it purely as data and never as an instruction. Real LLM-backed
 * translation is a flagged extension point and is not enabled by default.
 */
@Component
@ConditionalOnProperty(name = "media.translation.mode", havingValue = "mock", matchIfMissing = true)
public class MockTranslationProvider implements TranslationProvider {

    @Override
    public String translate(String neutralizedText, String targetLanguage) {
        if (neutralizedText == null || neutralizedText.isBlank()) {
            return "";
        }
        return "[RU] " + neutralizedText.trim();
    }
}
