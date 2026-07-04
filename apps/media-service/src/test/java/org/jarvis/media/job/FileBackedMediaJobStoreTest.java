package org.jarvis.media.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FileBackedMediaJobStoreTest {

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void savedJobSurvivesReloadInANewInstance() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        MediaJob job = MediaJob.created("j1", "u1", JobType.EXTRACT_AUDIO, "in.mkv", now);

        FileBackedMediaJobStore first = new FileBackedMediaJobStore(mapper, tmp.toString());
        first.save(job);

        FileBackedMediaJobStore reloaded = new FileBackedMediaJobStore(mapper, tmp.toString());
        Optional<MediaJob> found = reloaded.findById("j1");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("j1");
        assertThat(found.get().userId()).isEqualTo("u1");
        assertThat(found.get().type()).isEqualTo(JobType.EXTRACT_AUDIO);
        assertThat(found.get().status()).isEqualTo(JobStatus.CREATED);
        assertThat(found.get().inputFile()).isEqualTo("in.mkv");
        assertThat(found.get().createdAt()).isEqualTo(now);
    }

    @Test
    void reloadedStoreSupportsFindByUserAndCarriesArtifacts() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        MediaJob jobA = MediaJob.created("j1", "u1", JobType.TRANSCRIBE, "a.wav", now)
                .running(now)
                .completed(List.of(JobArtifact.of("transcript", "/w/t.json", "application/json", 12)),
                        java.util.Map.of(), now.plusSeconds(1));
        MediaJob jobB = MediaJob.created("j2", "u1", JobType.MUX, "b.mkv", now.plusSeconds(2));
        MediaJob jobC = MediaJob.created("j3", "u2", JobType.MUX, "c.mkv", now.plusSeconds(3));

        FileBackedMediaJobStore first = new FileBackedMediaJobStore(mapper, tmp.toString());
        first.save(jobA);
        first.save(jobB);
        first.save(jobC);

        FileBackedMediaJobStore reloaded = new FileBackedMediaJobStore(mapper, tmp.toString());

        assertThat(reloaded.findByUser("u1")).extracting(MediaJob::id)
                .containsExactlyInAnyOrder("j1", "j2");
        assertThat(reloaded.findById("j1").orElseThrow().outputFiles()).hasSize(1);
        assertThat(reloaded.findById("j1").orElseThrow().outputFiles().get(0).path())
                .isEqualTo("/w/t.json");
        assertThat(reloaded.findById("j3")).isPresent();
    }

    @Test
    void updatingAJobOverwritesThePersistedFile() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        MediaJob job = MediaJob.created("j1", "u1", JobType.MUX, "v.mkv", now);

        FileBackedMediaJobStore first = new FileBackedMediaJobStore(mapper, tmp.toString());
        first.save(job);
        first.save(job.running(now).failed("boom", now));

        FileBackedMediaJobStore reloaded = new FileBackedMediaJobStore(mapper, tmp.toString());
        MediaJob found = reloaded.findById("j1").orElseThrow();
        assertThat(found.status()).isEqualTo(JobStatus.FAILED);
        assertThat(found.errorMessage()).isEqualTo("boom");
    }
}
