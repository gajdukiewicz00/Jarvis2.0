package org.jarvis.media.job;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jarvis.media.support.MediaTestFactory;
import org.jarvis.media.support.SameThreadExecutorService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaJobServiceTest {

    @Test
    void submitRunsWorkSynchronouslyToCompleted() {
        MediaJobStore store = MediaTestFactory.store();
        MediaJobService service = MediaTestFactory.syncJobService(store);

        MediaJob created = service.submit(JobType.EXTRACT_AUDIO, "u1", "in.mkv",
                token -> JobOutcome.of(
                        List.of(JobArtifact.of("audio", "/w/a.wav", "audio/wav", 5)),
                        Map.of("ok", true)));

        MediaJob finished = service.getJob(created.id(), "u1");
        assertThat(finished.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(finished.outputFiles()).hasSize(1);
    }

    @Test
    void failingWorkMarksJobFailedWithMessage() {
        MediaJobStore store = MediaTestFactory.store();
        MediaJobService service = MediaTestFactory.syncJobService(store);

        MediaJob created = service.submit(JobType.TRANSCRIBE, "u1", "a.wav", token -> {
            throw new IllegalStateException("decode failed");
        });

        MediaJob finished = service.getJob(created.id(), "u1");
        assertThat(finished.status()).isEqualTo(JobStatus.FAILED);
        assertThat(finished.errorMessage()).isEqualTo("decode failed");
    }

    @Test
    void getJobIsScopedToOwner() {
        MediaJobStore store = MediaTestFactory.store();
        MediaJobService service = MediaTestFactory.syncJobService(store);
        MediaJob created = service.submit(JobType.MUX, "owner", "v.mkv",
                token -> JobOutcome.of(List.of(), Map.of()));

        assertThatThrownBy(() -> service.getJob(created.id(), "intruder"))
                .isInstanceOf(JobNotFoundException.class);
        assertThat(service.getJob(created.id(), "owner")).isNotNull();
    }

    @Test
    void listJobsReturnsOnlyOwnJobs() {
        MediaJobStore store = MediaTestFactory.store();
        MediaJobService service = MediaTestFactory.syncJobService(store);
        service.submit(JobType.MUX, "a", "v1", token -> JobOutcome.of(List.of(), Map.of()));
        service.submit(JobType.MUX, "b", "v2", token -> JobOutcome.of(List.of(), Map.of()));

        assertThat(service.listJobs("a")).hasSize(1);
        assertThat(service.listJobs("a").get(0).userId()).isEqualTo("a");
    }

    @Test
    void cancelAlreadyCompletedReturnsFalse() {
        MediaJobStore store = MediaTestFactory.store();
        MediaJobService service = MediaTestFactory.syncJobService(store);
        MediaJob created = service.submit(JobType.MUX, "u1", "v.mkv",
                token -> JobOutcome.of(List.of(), Map.of()));

        assertThat(service.cancel(created.id(), "u1")).isFalse();
    }

    @Test
    void cancelStopsRunningJobAndMarksCancelled() throws Exception {
        MediaJobStore store = MediaTestFactory.store();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        MediaJobService service = new MediaJobService(store, executor, Clock.systemUTC(), MediaTestFactory.metrics());

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        MediaJob created = service.submit(JobType.RUSSIAN_DUB_AUDIO, "u1", "t.json", token -> {
            started.countDown();
            // simulate a long, cancellable step
            for (int i = 0; i < 200; i++) {
                token.throwIfCancelled();
                release.await(20, TimeUnit.MILLISECONDS);
            }
            return JobOutcome.of(List.of(), Map.of());
        });

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        boolean cancelled = service.cancel(created.id(), "u1");
        release.countDown();

        assertThat(cancelled).isTrue();
        // give the worker a moment to observe cancellation
        MediaJob result = awaitTerminal(service, created.id());
        assertThat(result.status()).isEqualTo(JobStatus.CANCELLED);
        executor.shutdownNow();
    }

    @Test
    void completedJobRecordsCreatedRunningCompletedCountersAndDuration() {
        MediaJobStore store = MediaTestFactory.store();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MediaJobMetrics metrics = new MediaJobMetrics(registry);
        MediaJobService service = new MediaJobService(store, new SameThreadExecutorService(), Clock.systemUTC(), metrics);

        service.submit(JobType.EXTRACT_AUDIO, "u1", "in.mkv", token -> JobOutcome.of(List.of(), Map.of()));

        assertThat(counter(registry, "EXTRACT_AUDIO", "created")).isEqualTo(1.0);
        assertThat(counter(registry, "EXTRACT_AUDIO", "running")).isEqualTo(1.0);
        assertThat(counter(registry, "EXTRACT_AUDIO", "completed")).isEqualTo(1.0);
        assertThat(registry.get("media.job.duration").tag("type", "EXTRACT_AUDIO").timer().count()).isEqualTo(1);
    }

    @Test
    void failingWorkRecordsFailedCounterAndDuration() {
        MediaJobStore store = MediaTestFactory.store();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MediaJobMetrics metrics = new MediaJobMetrics(registry);
        MediaJobService service = new MediaJobService(store, new SameThreadExecutorService(), Clock.systemUTC(), metrics);

        service.submit(JobType.TRANSCRIBE, "u1", "a.wav", token -> {
            throw new IllegalStateException("decode failed");
        });

        assertThat(counter(registry, "TRANSCRIBE", "failed")).isEqualTo(1.0);
        assertThat(registry.get("media.job.duration").tag("type", "TRANSCRIBE").timer().count()).isEqualTo(1);
    }

    @Test
    void cancelRecordsCancelledCounter() throws Exception {
        MediaJobStore store = MediaTestFactory.store();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MediaJobMetrics metrics = new MediaJobMetrics(registry);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        MediaJobService service = new MediaJobService(store, executor, Clock.systemUTC(), metrics);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        MediaJob created = service.submit(JobType.MUX, "u1", "v.mkv", token -> {
            started.countDown();
            for (int i = 0; i < 200; i++) {
                token.throwIfCancelled();
                release.await(20, TimeUnit.MILLISECONDS);
            }
            return JobOutcome.of(List.of(), Map.of());
        });

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(service.cancel(created.id(), "u1")).isTrue();
        release.countDown();
        awaitTerminal(service, created.id());
        executor.shutdownNow();

        assertThat(counter(registry, "MUX", "cancelled")).isEqualTo(1.0);
    }

    @Test
    void cancelOnAlreadyTerminalJobDoesNotDoubleCount() {
        MediaJobStore store = MediaTestFactory.store();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MediaJobMetrics metrics = new MediaJobMetrics(registry);
        MediaJobService service = new MediaJobService(store, new SameThreadExecutorService(), Clock.systemUTC(), metrics);

        MediaJob created = service.submit(JobType.MUX, "u1", "v.mkv", token -> JobOutcome.of(List.of(), Map.of()));

        assertThat(service.cancel(created.id(), "u1")).isFalse();
        assertThat(counter(registry, "MUX", "completed")).isEqualTo(1.0);
        assertThat(registry.find("media.jobs").tag("type", "MUX").tag("status", "cancelled").counter()).isNull();
    }

    private double counter(SimpleMeterRegistry registry, String type, String status) {
        return registry.get("media.jobs").tag("type", type).tag("status", status).counter().count();
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
