package org.jarvis.media.mux;

import org.jarvis.media.ffmpeg.MockFFmpegClient;
import org.jarvis.media.job.JobArtifact;
import org.jarvis.media.job.JobStatus;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.job.MediaJobStore;
import org.jarvis.media.support.MediaTestFactory;
import org.jarvis.media.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MuxServiceTest {

    @TempDir
    Path tmp;

    private record Harness(MuxService service, MediaJobService jobs) {}

    private Harness harness() {
        MediaJobStore store = MediaTestFactory.store();
        MediaJobService jobs = MediaTestFactory.syncJobService(store);
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        return new Harness(new MuxService(jobs, new MockFFmpegClient(), ws), jobs);
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

    @Test
    void requiresAtLeastOneAddedTrack() {
        Harness h = harness();
        Path original = tmp.resolve("orig.mkv");
        assertThatThrownBy(() -> h.service().submit("u1",
                new MuxRequest(original.toString(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
