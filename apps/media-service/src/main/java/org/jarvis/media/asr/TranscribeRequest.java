package org.jarvis.media.asr;

import jakarta.validation.constraints.NotBlank;

/**
 * Transcription request.
 *
 * @param inputFile    path to the audio file to transcribe (typically an extracted artifact)
 * @param languageHint optional source-language hint for the ASR provider
 */
public record TranscribeRequest(
        @NotBlank String inputFile,
        String languageHint) {
}
