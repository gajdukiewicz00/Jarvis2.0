package org.jarvis.media.asr;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.process.ProcessResult;
import org.jarvis.media.process.ProcessRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Real ASR provider backed by a whisper.cpp CLI binary (e.g. {@code whisper-cli}
 * from https://github.com/ggerganov/whisper.cpp). Active only when
 * {@code media.asr.mode=whisper}.
 *
 * <p>The container image ships no whisper.cpp binary or GGML model by default (see
 * the {@code real-media-image} Maven profile / {@code docker/real-media-binaries}).
 * Rather than fail every transcription job in an environment that has the flag
 * turned on but hasn't opted into the real-binary image yet, this provider checks
 * for both the configured binary and model file BEFORE ever spawning a subprocess
 * and, when either is missing, falls back to the same deterministic output as
 * {@link MockAsrProvider} — logged as a warning so the degradation is observable,
 * never silent in the logs even though the caller sees a normal transcript.</p>
 *
 * <p>When both are present, invokes whisper.cpp with {@code -oj} (JSON output) and
 * parses the result via {@link WhisperJsonParser}. No shell is used — the argument
 * list goes straight to {@link ProcessRunner}, matching the no-shell posture of
 * {@code RealFFmpegClient}/{@code RealFFprobeClient}.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "media.asr.mode", havingValue = "whisper")
public class WhisperCppAsrProvider implements AsrProvider {

    private final ProcessRunner runner;
    private final MediaProperties props;
    private final WhisperJsonParser parser;
    private final AsrProvider fallback;

    public WhisperCppAsrProvider(ProcessRunner runner, MediaProperties props, WhisperJsonParser parser) {
        this.runner = runner;
        this.props = props;
        this.parser = parser;
        this.fallback = new MockAsrProvider();
    }

    @Override
    public Transcript transcribe(Path audioFile, String languageHint) {
        Path binary = resolveBinary(props.asr().binary());
        Path model = resolveModel(props.asr().modelPath());
        if (binary == null || model == null) {
            log.warn("whisper.cpp binary/model unavailable (binary={}, model={}); "
                            + "falling back to mock ASR for {}",
                    props.asr().binary(), props.asr().modelPath(), audioFile.getFileName());
            return fallback.transcribe(audioFile, languageHint);
        }
        Path outputBase = workingOutputBase(audioFile);
        try {
            List<String> command = buildCommand(binary, model, audioFile, outputBase, languageHint);
            ProcessResult result = runner.run(command, props.asr().timeoutSeconds());
            if (!result.isSuccess()) {
                throw new AsrException("whisper.cpp exited with code " + result.exitCode());
            }
            Path jsonOutput = Path.of(outputBase + ".json");
            if (!Files.isRegularFile(jsonOutput)) {
                throw new AsrException("whisper.cpp produced no transcript JSON output");
            }
            String json = Files.readString(jsonOutput, StandardCharsets.UTF_8);
            return parser.parse(json, languageHint);
        } catch (AsrException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AsrException("whisper.cpp interrupted");
        } catch (IOException e) {
            throw new AsrException("whisper.cpp execution failed: " + e.getMessage());
        } finally {
            cleanup(outputBase);
        }
    }

    private Path resolveModel(String modelPath) {
        if (modelPath == null || modelPath.isBlank()) {
            return null;
        }
        Path model = Path.of(modelPath);
        return Files.isRegularFile(model) ? model : null;
    }

    /** Resolves a configured binary, searching PATH for a bare name (mirrors OS exec resolution). */
    private Path resolveBinary(String binary) {
        if (binary == null || binary.isBlank()) {
            return null;
        }
        Path direct = Path.of(binary);
        if (binary.contains("/") || direct.isAbsolute()) {
            return (Files.isRegularFile(direct) && Files.isExecutable(direct)) ? direct : null;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path candidate = Path.of(dir, binary);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Path workingOutputBase(Path audioFile) {
        Path parent = audioFile.toAbsolutePath().normalize().getParent();
        Path dir = (parent != null && Files.isDirectory(parent))
                ? parent : Path.of(System.getProperty("java.io.tmpdir"));
        return dir.resolve("whisper-" + UUID.randomUUID());
    }

    private List<String> buildCommand(Path binary, Path model, Path audioFile, Path outputBase, String languageHint) {
        String language = (languageHint == null || languageHint.isBlank()) ? "auto" : languageHint;
        return List.of(
                binary.toString(),
                "-m", model.toString(),
                "-f", audioFile.toString(),
                "-l", language,
                "-oj",
                "-of", outputBase.toString());
    }

    private void cleanup(Path outputBase) {
        deleteQuietly(Path.of(outputBase + ".json"));
        deleteQuietly(Path.of(outputBase + ".txt"));
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup of scratch output files
        }
    }
}
