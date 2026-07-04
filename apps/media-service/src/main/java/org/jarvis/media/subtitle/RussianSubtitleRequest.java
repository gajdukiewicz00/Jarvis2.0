package org.jarvis.media.subtitle;

import jakarta.validation.constraints.NotBlank;

/**
 * Russian-subtitle request.
 *
 * @param transcriptFile path to a transcript.json artifact produced by transcription
 */
public record RussianSubtitleRequest(@NotBlank String transcriptFile) {
}
