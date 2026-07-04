package org.jarvis.memory.cv;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Inbound shape of a {@code jarvis.cv.screen_context.created} event, as
 * produced by vision-security-service's {@code ScreenContextResult}. Only the
 * fields memory-service persists are modelled; everything else is ignored so
 * the contract can evolve without breaking this consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScreenContextEvent(
        String userId,
        Instant capturedAt,
        long durationMs,
        String screenshotPath,
        String displayServer,
        String activeWindowTitle,
        String activeProcessName,
        List<String> semanticTags,
        List<Map<String, Object>> uiElements,
        List<Map<String, Object>> objects,
        Analysis analysis,
        boolean success,
        String error
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Analysis(
            String ocrText,
            List<Map<String, Object>> blocks,
            String engine,
            String language
    ) {
    }

    public String ocrText() {
        return analysis == null ? null : analysis.ocrText();
    }
}
