package org.jarvis.media.asr;

import org.jarvis.media.job.JobStatus;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.job.MediaJobStore;
import org.jarvis.media.support.MediaTestFactory;
import org.jarvis.media.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptionServiceTest {

    @TempDir
    Path tmp;

    @Test
    void emptyAudioYieldsEmptyTranscript() {
        MediaJobStore store = MediaTestFactory.store();
        MediaJobService jobs = MediaTestFactory.syncJobService(store);
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        TranscriptionService service = new TranscriptionService(jobs, new MockAsrProvider(), new TranscriptCodec(), ws);

        String audio = tmp.resolve("silence.wav").toString();
        MediaJob created = service.submit("u1", new TranscribeRequest(audio, "en"));

        MediaJob done = jobs.getJob(created.id(), "u1");
        assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.details()).containsEntry("empty", true);
        assertThat(done.details()).containsEntry("segmentCount", 0);
    }

    @Test
    void asrFailureMarksJobFailed() {
        MediaJobStore store = MediaTestFactory.store();
        MediaJobService jobs = MediaTestFactory.syncJobService(store);
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        TranscriptionService service = new TranscriptionService(jobs, new MockAsrProvider(), new TranscriptCodec(), ws);

        String audio = tmp.resolve("corrupt-fail.wav").toString();
        MediaJob created = service.submit("u1", new TranscribeRequest(audio, "en"));

        MediaJob done = jobs.getJob(created.id(), "u1");
        assertThat(done.status()).isEqualTo(JobStatus.FAILED);
        assertThat(done.errorMessage()).isNotBlank();
    }

    @Test
    void normalAudioCompletesWithThreeSegments() {
        MediaJobStore store = MediaTestFactory.store();
        MediaJobService jobs = MediaTestFactory.syncJobService(store);
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        TranscriptionService service = new TranscriptionService(jobs, new MockAsrProvider(), new TranscriptCodec(), ws);

        String audio = tmp.resolve("audio.wav").toString();
        MediaJob created = service.submit("u1", new TranscribeRequest(audio, "en"));

        MediaJob done = jobs.getJob(created.id(), "u1");
        assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.details()).containsEntry("segmentCount", 3);
        assertThat(done.outputFiles()).anyMatch(a -> a.kind().equals("transcript"));
    }
}
