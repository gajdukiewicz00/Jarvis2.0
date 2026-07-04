package org.jarvis.media.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simulated ffmpeg for tests and the binary-free container. Writes a small placeholder
 * to the output path so artifacts exist with a non-zero size, and pointedly NEVER
 * reads or writes the input — proving by construction that the original is preserved.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "media.ffmpeg.mode", havingValue = "mock", matchIfMissing = true)
public class MockFFmpegClient implements FFmpegClient {

    @Override
    public void extractAudio(Path input, int streamIndex, Path output, AudioFormat format) {
        writePlaceholder(output, "MOCK-AUDIO stream=" + streamIndex + " format=" + format.extension());
        log.debug("Mock extracted audio stream {} -> {}", streamIndex, output.getFileName());
    }

    @Override
    public void mux(Path originalVideo, Path russianSubtitle, Path russianAudio, Path output) {
        writePlaceholder(output, "MOCK-MUX base=" + originalVideo.getFileName()
                + " sub=" + (russianSubtitle != null) + " audio=" + (russianAudio != null));
        log.debug("Mock muxed -> {}", output.getFileName());
    }

    private void writePlaceholder(Path output, String marker) {
        try {
            Files.writeString(output, marker + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FFmpegException("mock ffmpeg could not write output: " + e.getMessage());
        }
    }
}
