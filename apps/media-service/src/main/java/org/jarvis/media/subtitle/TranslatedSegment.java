package org.jarvis.media.subtitle;

/** A subtitle cue: timing plus translated (Russian) text, retaining the speaker label. */
public record TranslatedSegment(int index, long startMs, long endMs, String text, String speakerId, Double confidence) {

    public long durationMs() {
        return Math.max(0, endMs - startMs);
    }

    public boolean isBlank() {
        return text == null || text.isBlank();
    }
}
