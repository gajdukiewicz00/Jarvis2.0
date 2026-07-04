package org.jarvis.visionsecurity.model;

import java.time.Instant;

/**
 * Response shape for {@code POST /api/v1/vision-security/cv/ask-screen}.
 * Wraps a {@link ScreenContextResult} plus the local VLM's answer (or an
 * honest "not configured / unavailable" status — Jarvis never fabricates
 * VLM output, and never calls cloud APIs).
 */
public record AskScreenResult(
        String question,
        String answer,
        ScreenContextResult screenContext,
        VlmInfo vlm,
        Instant capturedAt,
        long durationMs,
        boolean success,
        String error
) {
    public record VlmInfo(
            String provider,
            String model,
            String availability,
            long durationMs,
            String error
    ) {
    }
}
