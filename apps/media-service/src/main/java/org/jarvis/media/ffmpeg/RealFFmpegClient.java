package org.jarvis.media.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.process.ProcessResult;
import org.jarvis.media.process.ProcessRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Real ffmpeg client. Active only when {@code media.ffmpeg.mode=real}. Reads the
 * input, writes only the output. Never edits the source in place.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "media.ffmpeg.mode", havingValue = "real")
public class RealFFmpegClient implements FFmpegClient {

    private final ProcessRunner runner;
    private final FFmpegCommandBuilder builder;
    private final MediaProperties props;

    public RealFFmpegClient(ProcessRunner runner, FFmpegCommandBuilder builder, MediaProperties props) {
        this.runner = runner;
        this.builder = builder;
        this.props = props;
    }

    @Override
    public void extractAudio(Path input, int streamIndex, Path output, AudioFormat format) {
        requireInput(input);
        List<String> command = builder.extractAudio(props.ffmpeg().binary(), input, streamIndex, output, format);
        execute(command, "extract-audio", output);
    }

    @Override
    public void mux(Path originalVideo, Path russianSubtitle, Path russianAudio, Path output) {
        requireInput(originalVideo);
        List<String> command = builder.mux(props.ffmpeg().binary(), originalVideo, russianSubtitle, russianAudio, output);
        execute(command, "mux", output);
    }

    private void requireInput(Path input) {
        if (!Files.isRegularFile(input)) {
            throw new FFmpegException("Input file does not exist: " + input.getFileName());
        }
    }

    private void execute(List<String> command, String op, Path expectedOutput) {
        try {
            ProcessResult result = runner.run(command, props.ffmpeg().timeoutSeconds());
            if (!result.isSuccess()) {
                throw new FFmpegException("ffmpeg " + op + " exited with code " + result.exitCode());
            }
            if (!Files.isRegularFile(expectedOutput)) {
                throw new FFmpegException("ffmpeg " + op + " produced no output");
            }
        } catch (FFmpegException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FFmpegException("ffmpeg " + op + " interrupted");
        } catch (Exception e) {
            throw new FFmpegException("ffmpeg " + op + " failed: " + e.getMessage());
        }
    }
}
