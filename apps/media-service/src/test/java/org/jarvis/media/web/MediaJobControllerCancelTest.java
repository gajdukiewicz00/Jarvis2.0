package org.jarvis.media.web;

import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.job.JobOutcome;
import org.jarvis.media.job.JobStatus;
import org.jarvis.media.job.JobType;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.job.MediaJobStore;
import org.jarvis.media.support.MediaTestFactory;
import org.jarvis.media.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct (non-MockMvc) coverage of the job-cancel endpoint: already-terminal jobs
 * report {@code cancelled=false}, a genuinely running job is stopped cooperatively,
 * and cancellation is scoped to the owning caller.
 */
class MediaJobControllerCancelTest {

    @TempDir
    Path tmp;

    private MediaJobController controller;
    private MediaJobService jobService;

    @BeforeEach
    void setUp() {
        WorkspaceManager workspace = MediaTestFactory.workspace(tmp);
        MediaJobStore store = MediaTestFactory.store();
        jobService = MediaTestFactory.syncJobService(store);
        MediaProperties props = MediaTestFactory.props(tmp);
        MediaFeatureGate gate = new MediaFeatureGate(props);
        controller = new MediaJobController(jobService, gate, workspace);
    }

    private MockHttpServletRequest requestAs(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId);
        return request;
    }

    @Test
    void cancellingAnAlreadyCompletedJobReportsFalseAndLeavesStatusCompleted() {
        MediaJob created = jobService.submit(JobType.MUX, "u1", "v.mkv",
                token -> JobOutcome.of(List.of(), Map.of()));

        ResponseEntity<Map<String, Object>> response = controller.cancel(created.id(), requestAs("u1"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("cancelled", false);
        assertThat(response.getBody()).containsEntry("status", JobStatus.COMPLETED.name());
    }

    @Test
    void cancellingARunningJobStopsItCooperativelyAndReportsTrue() throws Exception {
        MediaJobStore store = MediaTestFactory.store();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        MediaJobService realtimeJobs = new MediaJobService(store, executor, Clock.systemUTC(), MediaTestFactory.metrics());
        MediaFeatureGate gate = new MediaFeatureGate(MediaTestFactory.props(tmp));
        MediaJobController realtimeController =
                new MediaJobController(realtimeJobs, gate, MediaTestFactory.workspace(tmp));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MediaJob created = realtimeJobs.submit(JobType.RUSSIAN_DUB_AUDIO, "u1", "t.json", token -> {
            started.countDown();
            for (int i = 0; i < 200; i++) {
                token.throwIfCancelled();
                release.await(20, TimeUnit.MILLISECONDS);
            }
            return JobOutcome.of(List.of(), Map.of());
        });

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        ResponseEntity<Map<String, Object>> response = realtimeController.cancel(created.id(), requestAs("u1"));
        release.countDown();

        assertThat(response.getBody()).containsEntry("cancelled", true);
        MediaJob finished = awaitTerminal(realtimeJobs, created.id());
        assertThat(finished.status()).isEqualTo(JobStatus.CANCELLED);
        executor.shutdownNow();
    }

    @Test
    void cancellingAJobNotOwnedByCallerIsRejected() {
        MediaJob created = jobService.submit(JobType.MUX, "owner", "v.mkv",
                token -> JobOutcome.of(List.of(), Map.of()));

        assertThatThrownBy(() -> controller.cancel(created.id(), requestAs("intruder")))
                .isInstanceOf(org.jarvis.media.job.JobNotFoundException.class);
    }

    private MediaJob awaitTerminal(MediaJobService service, String id) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            MediaJob job = service.getJob(id, "u1");
            if (job.status().isTerminal()) {
                return job;
            }
            Thread.sleep(20);
        }
        return service.getJob(id, "u1");
    }
}
