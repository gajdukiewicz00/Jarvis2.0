package org.jarvis.media.asr;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Deterministic ASR mock (the default; real whisper/Vosk integration is a follow-up).
 * Filename markers drive the three test paths so behavior is reproducible without audio:
 * <ul>
 *   <li>name contains {@code fail}/{@code corrupt} → {@link AsrException};</li>
 *   <li>name contains {@code empty}/{@code silence} → empty transcript;</li>
 *   <li>otherwise → a fixed multi-segment English transcript with timings.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "media.asr.mode", havingValue = "mock", matchIfMissing = true)
public class MockAsrProvider implements AsrProvider {

    @Override
    public Transcript transcribe(Path audioFile, String languageHint) {
        String name = audioFile.getFileName().toString().toLowerCase();
        if (name.contains("fail") || name.contains("corrupt")) {
            throw new AsrException("mock ASR decode failure for " + audioFile.getFileName());
        }
        String language = (languageHint == null || languageHint.isBlank()) ? "en" : languageHint;
        if (name.contains("empty") || name.contains("silence")) {
            return Transcript.empty(language);
        }
        return new Transcript(language, List.of(
                new TranscriptSegment(0, 0L, 2500L, "Good evening. Welcome back.", "S1", 0.94),
                new TranscriptSegment(1, 2600L, 6000L, "Today we look at the new engine.", "S1", 0.88),
                new TranscriptSegment(2, 6100L, 9000L, "Let's get started.", "S2", 0.91)));
    }
}
