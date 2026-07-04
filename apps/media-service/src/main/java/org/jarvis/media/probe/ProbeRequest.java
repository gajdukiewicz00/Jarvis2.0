package org.jarvis.media.probe;

import jakarta.validation.constraints.NotBlank;

/**
 * Probe request.
 *
 * @param inputFile          path to the media file (validated against allowed roots)
 * @param preferredLanguage  optional ISO language to prefer for the main audio track
 * @param overrideAudioIndex optional manual main-audio stream index
 */
public record ProbeRequest(
        @NotBlank String inputFile,
        String preferredLanguage,
        Integer overrideAudioIndex) {
}
