package org.jarvis.media.tts;

/** Outcome of synthesizing one segment: the produced duration and byte size. */
public record TtsResult(long synthesizedDurationMs, long sizeBytes) {

    public boolean isMissing() {
        return sizeBytes <= 0;
    }
}
