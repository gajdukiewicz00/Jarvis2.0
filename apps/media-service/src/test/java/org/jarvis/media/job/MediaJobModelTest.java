package org.jarvis.media.job;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MediaJobModelTest {

    private final Instant t0 = Instant.parse("2026-06-24T10:00:00Z");
    private final Instant t1 = Instant.parse("2026-06-24T10:00:05Z");

    @Test
    void transitionsAreImmutableAndProduceNewInstances() {
        MediaJob created = MediaJob.created("id1", "u1", JobType.EXTRACT_AUDIO, "in.mkv", t0);
        MediaJob running = created.running(t1);

        assertThat(created.status()).isEqualTo(JobStatus.CREATED);
        assertThat(running.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(running.createdAt()).isEqualTo(t0);
        assertThat(running.updatedAt()).isEqualTo(t1);
        assertThat(running).isNotSameAs(created);
    }

    @Test
    void completedCarriesArtifactsAndDetails() {
        MediaJob job = MediaJob.created("id1", "u1", JobType.TRANSCRIBE, "a.wav", t0)
                .running(t0)
                .completed(List.of(JobArtifact.of("transcript", "/w/t.json", "application/json", 12)),
                        Map.of("segmentCount", 3), t1);

        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.outputFiles()).hasSize(1);
        assertThat(job.details()).containsEntry("segmentCount", 3);
    }

    @Test
    void failedAndCancelledSetTerminalState() {
        MediaJob base = MediaJob.created("id1", "u1", JobType.MUX, "v.mkv", t0);
        assertThat(base.failed("boom", t1).status()).isEqualTo(JobStatus.FAILED);
        assertThat(base.failed("boom", t1).errorMessage()).isEqualTo("boom");
        assertThat(base.cancelled(t1).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(base.cancelled(t1).errorMessage()).isEqualTo("cancelled_by_user");
    }

    @Test
    void terminalStatesReportTerminal() {
        assertThat(JobStatus.CREATED.isTerminal()).isFalse();
        assertThat(JobStatus.RUNNING.isTerminal()).isFalse();
        assertThat(JobStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(JobStatus.FAILED.isTerminal()).isTrue();
        assertThat(JobStatus.CANCELLED.isTerminal()).isTrue();
    }
}
