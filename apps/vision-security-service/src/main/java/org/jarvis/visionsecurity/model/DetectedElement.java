package org.jarvis.visionsecurity.model;

/**
 * A single detected UI element or scene object on a screen/image.
 *
 * <p>Shared shape for both {@code uiElements} (button / input / tab / window …)
 * and {@code objects} (person / laptop / …) on a {@link ScreenContextResult}.
 * The {@code source} field records how the detection was produced so a
 * consumer can weigh it:</p>
 * <ul>
 *   <li>{@code ocr-rule} — derived deterministically from OCR text/bboxes</li>
 *   <li>{@code local-model} — produced by a local neural model (no cloud)</li>
 *   <li>{@code not-configured} — placeholder; never emitted as a real detection</li>
 * </ul>
 *
 * Jarvis never fabricates detections: when no detector is configured the
 * owning list is empty rather than populated with guesses.
 */
public record DetectedElement(
        String label,
        double confidence,
        RectBox bbox,
        String source
) {
    /** Canonical {@code source} values. */
    public static final String SOURCE_OCR_RULE = "ocr-rule";
    public static final String SOURCE_LOCAL_MODEL = "local-model";
    public static final String SOURCE_NOT_CONFIGURED = "not-configured";
}
