package org.jarvis.media.ffmpeg;

import jakarta.validation.constraints.NotBlank;

/**
 * Audio-extraction request.
 *
 * @param inputFile         media file path (validated against allowed roots)
 * @param audioStreamIndex  optional explicit stream index; if null the main audio is auto-selected
 * @param format            "wav" (default) or "flac"
 * @param preferredLanguage optional language preference used when auto-selecting
 */
public record ExtractAudioRequest(
        @NotBlank String inputFile,
        Integer audioStreamIndex,
        String format,
        String preferredLanguage) {
}
