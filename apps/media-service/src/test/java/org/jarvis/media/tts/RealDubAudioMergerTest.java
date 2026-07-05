package org.jarvis.media.tts;

import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Companion to {@code RealFFmpegClientTest}-style coverage: proves the real dub-track
 * merger builds a genuine ffmpeg invocation (rather than only being reachable in a
 * hand-built unit test) without requiring an actual ffmpeg binary.
 */
class RealDubAudioMergerTest {

    @TempDir
    Path tmp;

    private MediaProperties props(String ffmpegBinary) {
        return new MediaProperties(
                true,
                new MediaProperties.Workspace(tmp.toString(), "", 24),
                new MediaProperties.Executor(2, 32),
                new MediaProperties.Ffprobe("mock", "ffprobe", 30),
                new MediaProperties.Ffmpeg("real", ffmpegBinary, 5),
                new MediaProperties.Asr("mock", "whisper-cli", "", 120),
                new MediaProperties.Translation("mock", "http://llm-service:8091"),
                new MediaProperties.Tts("mock", false, "piper", "", 60),
                new MediaProperties.Subtitle(7, 0.5));
    }

    @Test
    void emptySegmentsWritesPlaceholderWithoutSpawningAProcess() {
        RealDubAudioMerger merger = new RealDubAudioMerger(
                new ProcessRunner(), new DubAudioMergeCommandBuilder(), props("/definitely/not/a/real/binary-xyz"));
        Path output = tmp.resolve("dub.ru.wav");

        merger.merge(List.of(), output);

        assertThat(Files.isRegularFile(output)).isTrue();
    }

    @Test
    void nonEmptySegmentsAttemptsRealExecutionAndFailsOnMissingOutput() throws IOException {
        Path seg = tmp.resolve("seg-0000.wav");
        Files.writeString(seg, "fake audio bytes");
        RealDubAudioMerger merger = new RealDubAudioMerger(
                new ProcessRunner(), new DubAudioMergeCommandBuilder(), props("/bin/true"));

        assertThatThrownBy(() -> merger.merge(
                List.of(new DubSegmentAudio(seg, new SegmentTimingPlan(0, 0, 1000, 1000, 1.0, 0, 0))),
                tmp.resolve("dub.ru.wav")))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("no output");
    }

    @Test
    void successfulFfmpegRunProducesTheCombinedTrack() throws IOException {
        Path seg = tmp.resolve("seg-0000.wav");
        Files.writeString(seg, "fake audio bytes");

        Path fakeFfmpeg = tmp.resolve("fake-ffmpeg.sh");
        Files.writeString(fakeFfmpeg, "#!/usr/bin/env bash\n"
                + "touch \"${@: -1}\"\n");
        Files.setPosixFilePermissions(fakeFfmpeg, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));

        RealDubAudioMerger merger = new RealDubAudioMerger(
                new ProcessRunner(), new DubAudioMergeCommandBuilder(), props(fakeFfmpeg.toString()));
        Path output = tmp.resolve("dub.ru.wav");

        merger.merge(List.of(new DubSegmentAudio(seg, new SegmentTimingPlan(0, 0, 1000, 1000, 1.0, 0, 0))), output);

        assertThat(Files.isRegularFile(output)).isTrue();
    }
}
