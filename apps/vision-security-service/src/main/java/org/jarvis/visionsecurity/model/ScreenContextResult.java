package org.jarvis.visionsecurity.model;

import java.time.Instant;
import java.util.List;

/**
 * Wide-view screen-context observation returned by
 * {@code POST /api/v1/vision-security/cv/screen-context}. Wraps a base
 * {@link CvAnalysisResult} with active-window metadata, optional layout
 * regions, semantic tags and a slot for a future local-VLM summary.
 *
 * The VLM block uses string fields rather than the adapter type so this
 * record stays serialisable independently from
 * {@code service.cv.LocalVlmAdapter}.
 *
 * <p>{@code uiElements} and {@code objects} are future-proof slots for UI
 * element / object detection. They are empty unless a local detector is
 * configured; the {@code detection} section carries the honest availability
 * of each detector so a consumer can tell "not configured" from "ran, found
 * nothing". Jarvis never fabricates detections.</p>
 */
public record ScreenContextResult(
        String userId,
        Instant capturedAt,
        long durationMs,
        String screenshotPath,
        String displayServer,
        String activeWindowTitle,
        String activeProcessName,
        List<String> semanticTags,
        List<RectBox> regions,
        List<DetectedElement> uiElements,
        List<DetectedElement> objects,
        DetectionSection detection,
        CvAnalysisResult analysis,
        VlmSection vlm,
        boolean success,
        String error
) {
    public record VlmSection(
            String availability,
            String engine,
            String summary,
            String error
    ) {
    }

    /**
     * Honest availability of the UI/object detectors for this capture.
     * Values are {@code READY | NOT_CONFIGURED | UNAVAILABLE}.
     */
    public record DetectionSection(
            String uiAvailability,
            String objectAvailability
    ) {
    }
}
