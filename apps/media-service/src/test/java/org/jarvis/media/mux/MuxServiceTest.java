package org.jarvis.media.mux;

import org.jarvis.media.ffmpeg.FFmpegClient;
import org.jarvis.media.ffmpeg.MockFFmpegClient;
import org.jarvis.media.job.JobArtifact;
import org.jarvis.media.job.JobStatus;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.job.MediaJobStore;
import org.jarvis.media.probe.MediaStream;
import org.jarvis.media.probe.ProbeRequest;
import org.jarvis.media.probe.ProbeResult;
import org.jarvis.media.probe.ProbeService;
import org.jarvis.media.support.MediaTestFactory;
import org.jarvis.media.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MuxServiceTest {

    @TempDir
    Path tmp;

    private record Harness(MuxService service, MediaJobService jobs) {}

    private Harness harness() {
        return harness(new MockFFmpegClient(), emptyProbeResult());
    }

    private Harness harness(FFmpegClient ffmpeg, ProbeResult probeResult) {
        MediaJobStore store = MediaTestFactory.store();
        MediaJobService jobs = MediaTestFactory.syncJobService(store);
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        ProbeService probe = mock(ProbeService.class);
        when(probe.probe(any(ProbeRequest.class))).thenReturn(probeResult);
        return new Harness(new MuxService(jobs, probe, ffmpeg, ws), jobs);
    }

    private static ProbeResult emptyProbeResult() {
        return new ProbeResult(List.of(), List.of(), List.of(), null, null);
    }

    @Test
    void muxesRussianTracksIntoNewFileLeavingOriginalUnchanged() throws Exception {
        Harness h = harness();
        Path original = tmp.resolve("orig.mkv");
        Files.writeString(original, "ORIGINAL-BYTES");
        Path subtitle = tmp.resolve("ru.srt");
        Files.writeString(subtitle, "1\n00:00:00,000 --> 00:00:01,000\nПривет\n");
        Path dub = tmp.resolve("ru.wav");
        Files.writeString(dub, "DUB");

        MediaJob created = h.service().submit("u1",
                new MuxRequest(original.toString(), subtitle.toString(), dub.toString(), "final.mkv"));
        MediaJob done = h.jobs().getJob(created.id(), "u1");

        assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.details()).containsEntry("originalPreserved", true);

        Path output = done.outputFiles().stream().filter(a -> a.kind().equals("video"))
                .map(JobArtifact::path).map(Path::of).findFirst().orElseThrow();
        assertThat(output).isNotEqualTo(original);
        assertThat(Files.exists(output)).isTrue();
        // original content is byte-for-byte unchanged
        assertThat(Files.readString(original)).isEqualTo("ORIGINAL-BYTES");
    }

    /**
     * Finding #19 regression: the new Russian audio/subtitle tracks must be tagged at the
     * original file's ACTUAL per-type stream count, not an assumed fixed index (1 for audio,
     * 0 for subtitle). This verifies MuxService probes the original and forwards the real
     * counts to FFmpegClient.mux() rather than hardcoding them.
     */
    @Test
    void passesProbedStreamCountsToFfmpegRatherThanAssumingFixedIndices() throws Exception {
        FFmpegClient ffmpeg = mock(FFmpegClient.class);
        MediaStream audio0 = new MediaStream(1, "audio", "aac", "eng", 2, 100.0, true, false, null);
        MediaStream audio1 = new MediaStream(2, "audio", "ac3", "eng", 6, 100.0, false, true, "commentary");
        MediaStream sub0 = new MediaStream(3, "subtitle", "subrip", "eng", null, null, false, false, null);
        ProbeResult probe = new ProbeResult(List.of(), List.of(audio0, audio1), List.of(sub0), 1, 100.0);
        Harness h = harness(ffmpeg, probe);

        Path original = tmp.resolve("orig.mkv");
        Files.writeString(original, "ORIGINAL-BYTES");
        Path subtitle = tmp.resolve("ru.srt");
        Files.writeString(subtitle, "1\n00:00:00,000 --> 00:00:01,000\nПривет\n");
        Path dub = tmp.resolve("ru.wav");
        Files.writeString(dub, "DUB");

        h.service().submit("u1",
                new MuxRequest(original.toString(), subtitle.toString(), dub.toString(), "final.mkv"));

        // original has 2 pre-existing audio streams and 1 pre-existing subtitle stream, so
        // the appended Russian tracks land at output indices 2 and 1 respectively — not the
        // previously hardcoded 1 and 0.
        verify(ffmpeg).mux(eq(original), eq(subtitle), eq(dub), eq(2), eq(1), any(Path.class));
    }

    @Test
    void requiresAtLeastOneAddedTrack() {
        Harness h = harness();
        Path original = tmp.resolve("orig.mkv");
        assertThatThrownBy(() -> h.service().submit("u1",
                new MuxRequest(original.toString(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
