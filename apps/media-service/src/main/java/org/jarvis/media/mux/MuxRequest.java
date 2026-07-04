package org.jarvis.media.mux;

import jakarta.validation.constraints.NotBlank;

/**
 * Mux request: add a Russian subtitle and/or Russian audio track to a copy of the
 * original. At least one of {@code subtitleFile} / {@code dubAudioFile} is required.
 *
 * @param originalFile original media (read-only input; never modified)
 * @param subtitleFile optional Russian subtitle artifact (.srt)
 * @param dubAudioFile optional Russian dub audio artifact
 * @param outputName   optional output filename (relative; defaults to output.mkv)
 */
public record MuxRequest(
        @NotBlank String originalFile,
        String subtitleFile,
        String dubAudioFile,
        String outputName) {
}
