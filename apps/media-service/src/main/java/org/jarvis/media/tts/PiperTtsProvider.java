package org.jarvis.media.tts;

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
 * Real TTS provider backed by a Piper CLI binary (https://github.com/rhasspy/piper)
 * and a Russian neutral-voice ONNX model. Active only when {@code media.tts.mode=real}.
 *
 * <p>Mirrors {@code WhisperCppAsrProvider}'s degrade-safe posture: the container image
 * ships neither the Piper binary nor a voice model by default (see the
 * {@code real-media-image} Maven profile), so this provider checks for both BEFORE ever
 * spawning a subprocess and, when either is missing, falls back to
 * {@link NeutralRussianTtsProvider} — logged as a warning so the degradation is
 * observable, never a silently different behavior.</p>
 *
 * <p>Piper always synthesizes with the single configured neutral voice model regardless
 * of the requested {@link VoiceProfile} — this MVP never clones a real person's voice,
 * even when a USER_OWNED profile is otherwise authorized by {@link VoiceProfileFactory}.
 * Text is written to a scratch stdin file and piped in via {@link ProcessRunner}'s stdin
 * redirect — never a shell, never string-concatenated into the argument list:
 * {@code piper --model <voice.onnx> --output_file <out.wav> < text-file}. The produced
 * WAV header is parsed via {@link WavAudioUtil} to compute the real synthesized
 * duration, replacing the text-length estimate the mock provider uses.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "media.tts.mode", havingValue = "real")
public class PiperTtsProvider implements TtsProvider {

    private final ProcessRunner runner;
    private final MediaProperties props;
    private final TtsProvider fallback;

    public PiperTtsProvider(ProcessRunner runner, MediaProperties props) {
        this.runner = runner;
        this.props = props;
        this.fallback = new NeutralRussianTtsProvider();
    }

    @Override
    public TtsResult synthesize(String text, VoiceProfile profile, Path output) {
        if (text == null || text.isBlank()) {
            return new TtsResult(0, 0);
        }
        Path binary = resolveBinary(props.tts().binary());
        Path model = resolveModel(props.tts().voiceModelPath());
        if (binary == null || model == null) {
            log.warn("Piper binary/voice model unavailable (binary={}, model={}); "
                            + "falling back to neutral placeholder TTS",
                    props.tts().binary(), props.tts().voiceModelPath());
            return fallback.synthesize(text, profile, output);
        }

        Path stdinFile = scratchFile(output);
        try {
            Files.writeString(stdinFile, text, StandardCharsets.UTF_8);
            List<String> command = buildCommand(binary, model, output);
            ProcessResult result = runner.run(command, props.tts().timeoutSeconds(), stdinFile);
            if (!result.isSuccess()) {
                throw new TtsException("piper exited with code " + result.exitCode());
            }
            if (!Files.isRegularFile(output)) {
                throw new TtsException("piper produced no audio output");
            }
            long durationMs = WavAudioUtil.durationMillis(output);
            long size = Files.size(output);
            return new TtsResult(durationMs, size);
        } catch (TtsException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TtsException("piper interrupted");
        } catch (IOException e) {
            throw new TtsException("piper execution failed: " + e.getMessage());
        } finally {
            deleteQuietly(stdinFile);
        }
    }

    private List<String> buildCommand(Path binary, Path model, Path output) {
        return List.of(binary.toString(), "--model", model.toString(), "--output_file", output.toString());
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

    private Path scratchFile(Path output) {
        Path parent = output.toAbsolutePath().normalize().getParent();
        Path dir = (parent != null && Files.isDirectory(parent)) ? parent : Path.of(System.getProperty("java.io.tmpdir"));
        return dir.resolve("piper-" + UUID.randomUUID() + ".txt");
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup of the scratch stdin file
        }
    }
}
