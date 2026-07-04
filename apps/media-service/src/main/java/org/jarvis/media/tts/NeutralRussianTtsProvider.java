package org.jarvis.media.tts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Default neutral Russian TTS (placeholder synthesis). Writes a marker file per segment
 * and estimates duration from text length so the dubbing quality report can detect
 * timing mismatches. It NEVER clones a real person's voice — even for a USER_OWNED
 * profile this MVP synthesizes the same neutral placeholder, so no impersonation is
 * possible. Filename/text marker {@code tts-fail} drives the failure test path.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "media.tts.mode", havingValue = "mock", matchIfMissing = true)
public class NeutralRussianTtsProvider implements TtsProvider {

    /** Rough speaking rate used to estimate synthetic duration from text length. */
    private static final long MILLIS_PER_CHAR = 65;

    @Override
    public TtsResult synthesize(String text, VoiceProfile profile, Path output) {
        if (text != null && text.toLowerCase().contains("tts-fail")) {
            throw new TtsException("mock TTS synthesis failure");
        }
        if (text == null || text.isBlank()) {
            return new TtsResult(0, 0);
        }
        String marker = "MOCK-TTS voice=" + profile.mode() + ":" + profile.voiceId()
                + " chars=" + text.length() + "\n" + text + "\n";
        try {
            Files.writeString(output, marker, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TtsException("mock TTS could not write output: " + e.getMessage());
        }
        long durationMs = (long) text.trim().length() * MILLIS_PER_CHAR;
        long size = output.toFile().length();
        return new TtsResult(durationMs, size);
    }
}
