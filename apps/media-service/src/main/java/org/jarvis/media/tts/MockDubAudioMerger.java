package org.jarvis.media.tts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Simulated dub-track merge for tests and the binary-free container. Writes a small
 * placeholder to the output path so the {@code dub-audio} artifact exists with a
 * non-zero size, without needing ffmpeg. Active by default (mirrors {@link
 * org.jarvis.media.ffmpeg.MockFFmpegClient}'s {@code media.ffmpeg.mode=mock} gate).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "media.ffmpeg.mode", havingValue = "mock", matchIfMissing = true)
public class MockDubAudioMerger implements DubAudioMerger {

    @Override
    public void merge(List<DubSegmentAudio> segments, Path output) {
        String marker = "MOCK-DUB-COMBINED segments=" + segments.size() + "\n";
        try {
            Files.writeString(output, marker, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TtsException("mock dub merge could not write output: " + e.getMessage());
        }
        log.debug("Mock-merged {} dub segment(s) -> {}", segments.size(), output.getFileName());
    }
}
