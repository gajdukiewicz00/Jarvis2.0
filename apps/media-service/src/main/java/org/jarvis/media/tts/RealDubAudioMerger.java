package org.jarvis.media.tts;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.process.ProcessResult;
import org.jarvis.media.process.ProcessRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Real dub-track merge, backed by ffmpeg. Active only when {@code media.ffmpeg.mode=real}
 * (the same flag that gates {@code RealFFmpegClient}, since it is the same ffmpeg binary
 * dependency). Reads only the per-segment clips; writes only the combined output.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "media.ffmpeg.mode", havingValue = "real")
public class RealDubAudioMerger implements DubAudioMerger {

    private final ProcessRunner runner;
    private final DubAudioMergeCommandBuilder builder;
    private final MediaProperties props;

    public RealDubAudioMerger(ProcessRunner runner, DubAudioMergeCommandBuilder builder, MediaProperties props) {
        this.runner = runner;
        this.builder = builder;
        this.props = props;
    }

    @Override
    public void merge(List<DubSegmentAudio> segments, Path output) {
        if (segments == null || segments.isEmpty()) {
            log.warn("No dub segments produced audio; writing an empty placeholder combined track for {}",
                    output.getFileName());
            writeEmptyPlaceholder(output);
            return;
        }
        List<String> command = builder.merge(props.ffmpeg().binary(), segments, output);
        try {
            ProcessResult result = runner.run(command, props.ffmpeg().timeoutSeconds());
            if (!result.isSuccess()) {
                throw new TtsException("ffmpeg dub-merge exited with code " + result.exitCode());
            }
            if (!Files.isRegularFile(output)) {
                throw new TtsException("ffmpeg dub-merge produced no output");
            }
        } catch (TtsException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TtsException("ffmpeg dub-merge interrupted");
        } catch (IOException e) {
            throw new TtsException("ffmpeg dub-merge failed: " + e.getMessage());
        }
    }

    private void writeEmptyPlaceholder(Path output) {
        try {
            Files.writeString(output, "EMPTY-DUB-TRACK\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TtsException("could not write empty dub track: " + e.getMessage());
        }
    }
}
