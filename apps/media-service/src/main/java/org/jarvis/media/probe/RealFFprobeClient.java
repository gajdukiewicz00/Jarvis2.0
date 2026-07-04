package org.jarvis.media.probe;

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
 * Real ffprobe client. Active only when {@code media.ffprobe.mode=real}. Builds an
 * explicit argument list (binary + flags + the file path as a single literal arg),
 * so a filename containing spaces, quotes, or shell metacharacters can never inject
 * a command. The container image ships no ffprobe binary, so prod defaults to mock.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "media.ffprobe.mode", havingValue = "real")
public class RealFFprobeClient implements FFprobeClient {

    private final ProcessRunner runner;
    private final FFprobeCommandBuilder commandBuilder;
    private final MediaProperties props;

    public RealFFprobeClient(ProcessRunner runner, FFprobeCommandBuilder commandBuilder, MediaProperties props) {
        this.runner = runner;
        this.commandBuilder = commandBuilder;
        this.props = props;
    }

    @Override
    public String probeJson(Path mediaFile) {
        if (!Files.isRegularFile(mediaFile)) {
            throw new ProbeException("Input file does not exist: " + mediaFile.getFileName());
        }
        List<String> command = commandBuilder.build(props.ffprobe().binary(), mediaFile);
        try {
            ProcessResult result = runner.run(command, props.ffprobe().timeoutSeconds());
            if (!result.isSuccess()) {
                throw new ProbeException("ffprobe exited with code " + result.exitCode());
            }
            return result.stdout();
        } catch (ProbeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProbeException("ffprobe interrupted", e);
        } catch (Exception e) {
            throw new ProbeException("ffprobe execution failed", e);
        }
    }
}
