package org.jarvis.media.asr;

import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers provider selection's runtime fallback contract: {@code media.asr.mode=whisper}
 * still degrades to the exact same deterministic output as {@link MockAsrProvider} when
 * the configured binary or model is absent, rather than failing every transcription job
 * in an environment that hasn't opted into the real-binary image.
 */
class WhisperCppAsrProviderTest {

    @TempDir
    Path tmp;

    private final MockAsrProvider mock = new MockAsrProvider();

    private WhisperCppAsrProvider provider(String binary, String modelPath, int timeoutSeconds) {
        MediaProperties props = new MediaProperties(
                true,
                new MediaProperties.Workspace(tmp.toString(), "", 24),
                new MediaProperties.Executor(2, 32),
                new MediaProperties.Ffprobe("mock", "ffprobe", 30),
                new MediaProperties.Ffmpeg("mock", "ffmpeg", 600),
                new MediaProperties.Asr("whisper", binary, modelPath, timeoutSeconds),
                new MediaProperties.Translation("mock", "http://llm-service:8091"),
                new MediaProperties.Tts("mock", false, "piper", "", 60),
                new MediaProperties.Subtitle(7, 0.5));
        return new WhisperCppAsrProvider(new ProcessRunner(), props, new WhisperJsonParser());
    }

    @Test
    void fallsBackToMockWhenModelPathIsBlank() throws IOException {
        Path audio = tmp.resolve("movie.wav");
        Files.writeString(audio, "fake audio bytes");
        WhisperCppAsrProvider provider = provider("/bin/true", "", 5);

        Transcript real = provider.transcribe(audio, null);
        Transcript expected = mock.transcribe(audio, null);

        assertThat(real).isEqualTo(expected);
        assertThat(real.segments()).isNotEmpty();
    }

    @Test
    void fallsBackToMockWhenBinaryDoesNotExist() throws IOException {
        Path audio = tmp.resolve("movie.wav");
        Files.writeString(audio, "fake audio bytes");
        Path model = tmp.resolve("ggml-base.bin");
        Files.writeString(model, "fake model bytes");

        WhisperCppAsrProvider provider = provider("/definitely/not/a/real/binary-xyz", model.toString(), 5);

        Transcript real = provider.transcribe(audio, null);
        Transcript expected = mock.transcribe(audio, null);

        assertThat(real).isEqualTo(expected);
    }

    @Test
    void fallbackAlsoReproducesMockFailurePathForFailFilenames() throws IOException {
        Path audio = tmp.resolve("fail-clip.wav");
        Files.writeString(audio, "fake audio bytes");
        WhisperCppAsrProvider provider = provider("/definitely/not/a/real/binary-xyz", "", 5);

        // Proves the fallback is a genuine MockAsrProvider delegate, not a different
        // "always succeeds" degraded behavior: mock's own fail-filename path still applies.
        assertThatThrownBy(() -> provider.transcribe(audio, null)).isInstanceOf(AsrException.class);
    }

    @Test
    void withBinaryAndModelPresentAttemptsRealExecutionAndFailsOnMissingOutput() throws IOException {
        Path audio = tmp.resolve("movie.wav");
        Files.writeString(audio, "fake audio bytes");
        Path model = tmp.resolve("ggml-base.bin");
        Files.writeString(model, "fake model bytes");

        // /bin/true exists, is executable, exits 0 — but (unlike real whisper.cpp) never
        // writes a transcript JSON file, so the provider must treat this as a real
        // execution attempt and fail on the missing expected output, NOT silently
        // fall back to mock (both binary and model genuinely "exist" here).
        WhisperCppAsrProvider provider = provider("/bin/true", model.toString(), 5);

        assertThatThrownBy(() -> provider.transcribe(audio, "en"))
                .isInstanceOf(AsrException.class)
                .hasMessageContaining("no transcript JSON output");
    }
}
