package org.jarvis.media.tts;

import jakarta.validation.constraints.NotBlank;

/**
 * Russian dubbing request.
 *
 * @param transcriptFile   path to a Russian transcript.json artifact (from the subtitle step)
 * @param voiceProfileMode "neutral" (default) or "user_owned"
 * @param voiceId          provider voice id for USER_OWNED mode
 * @param consentConfirmed user attests the voice is theirs (required for USER_OWNED)
 */
public record RussianDubRequest(
        @NotBlank String transcriptFile,
        String voiceProfileMode,
        String voiceId,
        boolean consentConfirmed) {
}
