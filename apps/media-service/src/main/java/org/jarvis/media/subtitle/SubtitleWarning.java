package org.jarvis.media.subtitle;

/**
 * A quality concern about a generated subtitle cue.
 *
 * @param type         one of LONG_SEGMENT, LOW_CONFIDENCE, OVERLAP, EMPTY_TRANSLATION
 * @param segmentIndex the affected segment index
 * @param message      human-readable detail
 */
public record SubtitleWarning(String type, int segmentIndex, String message) {

    public static final String LONG_SEGMENT = "LONG_SEGMENT";
    public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";
    public static final String OVERLAP = "OVERLAP";
    public static final String EMPTY_TRANSLATION = "EMPTY_TRANSLATION";
}
