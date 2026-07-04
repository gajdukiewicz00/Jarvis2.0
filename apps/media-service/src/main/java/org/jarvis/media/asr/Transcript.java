package org.jarvis.media.asr;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A full transcript. Segment text is untrusted external data; it must pass through
 * the media text guard before being used in any LLM prompt (see C5 translation).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Transcript(String language, List<TranscriptSegment> segments) {

    public Transcript {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return segments.isEmpty();
    }

    public static Transcript empty(String language) {
        return new Transcript(language, List.of());
    }
}
