package org.jarvis.media.asr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One timed transcript segment.
 *
 * @param index      ordinal position
 * @param startMs    start time in milliseconds
 * @param endMs      end time in milliseconds
 * @param text       recognized text (UNTRUSTED — never fed to an LLM without the text guard)
 * @param speakerId  optional speaker label, else null
 * @param confidence optional 0..1 confidence, else null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TranscriptSegment(
        int index,
        long startMs,
        long endMs,
        String text,
        String speakerId,
        Double confidence) {

    public long durationMs() {
        return Math.max(0, endMs - startMs);
    }
}
