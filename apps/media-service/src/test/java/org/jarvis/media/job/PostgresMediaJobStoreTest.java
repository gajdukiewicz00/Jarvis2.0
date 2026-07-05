package org.jarvis.media.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link PostgresMediaJobStore} — real JDBC + a real Flyway migration run
 * — against an embedded, Docker-free H2 database in PostgreSQL compatibility mode
 * rather than Testcontainers, so this suite has no external dependency. Each test
 * uses a fresh randomly-named in-memory database (kept alive via {@code
 * DB_CLOSE_DELAY=-1}) so two store instances pointed at the same URL can prove
 * durability across separate {@code PostgresMediaJobStore} construction, exactly
 * like {@code FileBackedMediaJobStoreTest} proves durability across separate
 * instances of the file-backed store.
 */
class PostgresMediaJobStoreTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private String freshDatabaseUrl() {
        return "jdbc:h2:mem:media-jobs-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    }

    private PostgresMediaJobStore store(String url) {
        return new PostgresMediaJobStore(mapper, url, "sa", "");
    }

    @Test
    void savedJobSurvivesReloadInANewStoreInstance() {
        String url = freshDatabaseUrl();
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        MediaJob job = MediaJob.created("j1", "u1", JobType.EXTRACT_AUDIO, "in.mkv", now);

        PostgresMediaJobStore first = store(url);
        first.save(job);

        PostgresMediaJobStore reloaded = store(url);
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
    void findByIdOnUnknownIdReturnsEmpty() {
        PostgresMediaJobStore s = store(freshDatabaseUrl());

        assertThat(s.findById("does-not-exist")).isEmpty();
    }

    @Test
    void reloadedStoreSupportsFindByUserCarriesArtifactsAndIsolatesOtherUsers() {
        String url = freshDatabaseUrl();
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        MediaJob jobA = MediaJob.created("j1", "u1", JobType.TRANSCRIBE, "a.wav", now)
                .running(now)
                .completed(List.of(JobArtifact.of("transcript", "/w/t.json", "application/json", 12)),
                        java.util.Map.of(), now.plusSeconds(1));
        MediaJob jobB = MediaJob.created("j2", "u1", JobType.MUX, "b.mkv", now.plusSeconds(2));
        MediaJob jobC = MediaJob.created("j3", "u2", JobType.MUX, "c.mkv", now.plusSeconds(3));

        PostgresMediaJobStore first = store(url);
        first.save(jobA);
        first.save(jobB);
        first.save(jobC);

        PostgresMediaJobStore reloaded = store(url);

        assertThat(reloaded.findByUser("u1")).extracting(MediaJob::id)
                .containsExactlyInAnyOrder("j1", "j2");
        assertThat(reloaded.findById("j1").orElseThrow().outputFiles()).hasSize(1);
        assertThat(reloaded.findById("j1").orElseThrow().outputFiles().get(0).path())
                .isEqualTo("/w/t.json");
        assertThat(reloaded.findByUser("u2")).extracting(MediaJob::id).containsExactly("j3");
    }

    @Test
    void findByUserOrdersMostRecentFirst() {
        String url = freshDatabaseUrl();
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        PostgresMediaJobStore s = store(url);
        s.save(MediaJob.created("older", "u1", JobType.MUX, "a.mkv", now));
        s.save(MediaJob.created("newer", "u1", JobType.MUX, "b.mkv", now.plusSeconds(60)));

        assertThat(s.findByUser("u1")).extracting(MediaJob::id).containsExactly("newer", "older");
    }

    @Test
    void updatingAJobOverwritesThePersistedRow() {
        String url = freshDatabaseUrl();
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        MediaJob job = MediaJob.created("j1", "u1", JobType.MUX, "v.mkv", now);

        PostgresMediaJobStore first = store(url);
        first.save(job);
        first.save(job.running(now).failed("boom", now));

        PostgresMediaJobStore reloaded = store(url);
        MediaJob found = reloaded.findById("j1").orElseThrow();

        assertThat(found.status()).isEqualTo(JobStatus.FAILED);
        assertThat(found.errorMessage()).isEqualTo("boom");
    }

    @Test
    void constructingASecondStoreAgainstAnAlreadyMigratedDatabaseDoesNotFail() {
        // Flyway must no-op (not error) on an already-up-to-date schema history —
        // this is exactly what happens on every pod restart against a real Postgres.
        String url = freshDatabaseUrl();
        store(url);

        org.assertj.core.api.Assertions.assertThatCode(() -> store(url)).doesNotThrowAnyException();
    }
}
