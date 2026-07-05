package org.jarvis.media.tts;

import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link PiperTtsProvider}'s runtime fallback contract (mirrors {@code
 * WhisperCppAsrProviderTest}): {@code media.tts.mode=real} still degrades to the exact
 * same neutral placeholder as {@link NeutralRussianTtsProvider} when the configured
 * binary or voice model is absent, and a genuinely present binary is actually invoked
 * (proven with a fake CLI script — no real Piper binary required).
 */
class PiperTtsProviderTest {

    @TempDir
    Path tmp;

    private final NeutralRussianTtsProvider fallback = new NeutralRussianTtsProvider();

    private PiperTtsProvider provider(String binary, String modelPath, int timeoutSeconds) {
        MediaProperties props = new MediaProperties(
                true,
                new MediaProperties.Workspace(tmp.toString(), "", 24),
                new MediaProperties.Executor(2, 32),
                new MediaProperties.Ffprobe("mock", "ffprobe", 30),
                new MediaProperties.Ffmpeg("mock", "ffmpeg", 600),
                new MediaProperties.Asr("mock", "whisper-cli", "", 120),
                new MediaProperties.Translation("mock", "http://llm-service:8091"),
                new MediaProperties.Tts("real", false, binary, modelPath, timeoutSeconds),
                new MediaProperties.Subtitle(7, 0.5));
        return new PiperTtsProvider(new ProcessRunner(), props);
    }

    @Test
    void blankTextNeverInvokesTheBinary() {
        PiperTtsProvider provider = provider("/definitely/not/a/real/binary-xyz", "", 5);
        TtsResult result = provider.synthesize("   ", VoiceProfile.neutral(), tmp.resolve("out.wav"));
        assertThat(result.isMissing()).isTrue();
    }

    @Test
    void fallsBackToNeutralWhenVoiceModelPathIsBlank() {
        PiperTtsProvider provider = provider("/bin/true", "", 5);
        Path output = tmp.resolve("seg.wav");

        TtsResult real = provider.synthesize("Привет", VoiceProfile.neutral(), output);
        TtsResult expected = fallback.synthesize("Привет", VoiceProfile.neutral(), tmp.resolve("expected.wav"));

        assertThat(real.synthesizedDurationMs()).isEqualTo(expected.synthesizedDurationMs());
        assertThat(real.isMissing()).isFalse();
    }

    @Test
    void fallsBackToNeutralWhenBinaryDoesNotExist() throws IOException {
        Path model = tmp.resolve("ru-neutral.onnx");
        Files.writeString(model, "fake-onnx-bytes");
        PiperTtsProvider provider = provider("/definitely/not/a/real/binary-xyz", model.toString(), 5);
        Path output = tmp.resolve("seg.wav");

        TtsResult real = provider.synthesize("Привет", VoiceProfile.neutral(), output);

        assertThat(real.isMissing()).isFalse();
        assertThat(Files.exists(output)).isTrue();
    }

    @Test
    void fallbackAlsoReproducesNeutralFailurePathForFailMarkerText() {
        PiperTtsProvider provider = provider("/definitely/not/a/real/binary-xyz", "", 5);

        assertThatThrownBy(() -> provider.synthesize("this will tts-fail", VoiceProfile.neutral(), tmp.resolve("out.wav")))
                .isInstanceOf(TtsException.class);
    }

    @Test
    void withBinaryAndModelPresentAttemptsRealExecutionAndFailsOnMissingOutput() throws IOException {
        Path model = tmp.resolve("ru-neutral.onnx");
        Files.writeString(model, "fake-onnx-bytes");
        // /bin/true exists, is executable, exits 0 — but (unlike real Piper) never writes
        // an output file, so the provider must treat this as a real execution attempt and
        // fail on the missing expected output, NOT silently fall back to the placeholder.
        PiperTtsProvider provider = provider("/bin/true", model.toString(), 5);

        assertThatThrownBy(() -> provider.synthesize("Привет", VoiceProfile.neutral(), tmp.resolve("out.wav")))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("no audio output");
    }

    @Test
    void realSynthesisParsesActualWavDurationFromTheProducedOutput() throws IOException {
        Path model = tmp.resolve("ru-neutral.onnx");
        Files.writeString(model, "fake-onnx-bytes");

        Path fixture = tmp.resolve("fixture.wav");
        Files.write(fixture, minimalPcmWav(16000, 1, 16, 32000)); // 1 second of audio

        Path fakePiper = tmp.resolve("fake-piper.sh");
        Files.writeString(fakePiper, "#!/usr/bin/env bash\n"
                + "cp \"" + fixture.toAbsolutePath() + "\" \"${@: -1}\"\n");
        Files.setPosixFilePermissions(fakePiper, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));

        PiperTtsProvider provider = provider(fakePiper.toString(), model.toString(), 5);
        Path output = tmp.resolve("out/seg-0000.wav");
        Files.createDirectories(output.getParent());

        TtsResult result = provider.synthesize("Привет, мир", VoiceProfile.neutral(), output);

        assertThat(result.synthesizedDurationMs()).isEqualTo(1000);
        assertThat(result.sizeBytes()).isGreaterThan(0);
    }

    private byte[] minimalPcmWav(int sampleRate, int channels, int bitsPerSample, int dataBytes) throws IOException {
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);
        ByteArrayOutputStream fmt = new ByteArrayOutputStream();
        fmt.write(int16LE(1));
        fmt.write(int16LE(channels));
        fmt.write(int32LE(sampleRate));
        fmt.write(int32LE(byteRate));
        fmt.write(int16LE(blockAlign));
        fmt.write(int16LE(bitsPerSample));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.write(int32LE(4 + 8 + fmt.size() + 8 + dataBytes));
        out.write("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
        out.write(int32LE(fmt.size()));
        out.write(fmt.toByteArray());
        out.write("data".getBytes(StandardCharsets.US_ASCII));
        out.write(int32LE(dataBytes));
        out.write(new byte[dataBytes]);
        return out.toByteArray();
    }

    private byte[] int32LE(int value) {
        return new byte[]{
                (byte) (value & 0xFF), (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF), (byte) ((value >> 24) & 0xFF)};
    }

    private byte[] int16LE(int value) {
        return new byte[]{(byte) (value & 0xFF), (byte) ((value >> 8) & 0xFF)};
    }
}
