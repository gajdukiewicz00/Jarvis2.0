package org.jarvis.media.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code MediaJobStore} bean selection wired through {@code
 * @ConditionalOnProperty("jarvis.media.job-store")}: with no property set at all,
 * {@link FileBackedMediaJobStore} is the effective default (durable across a pod
 * restart), mirroring the agent-service task-store pattern. {@link
 * InMemoryMediaJobStore} only activates when explicitly requested. {@code
 * PostgresMediaJobStore} is intentionally excluded from this scan — constructing it
 * runs a real Flyway migration against a JDBC URL (see {@code PostgresMediaJobStoreTest}
 * for that store's dedicated, H2-backed coverage), so it does not belong in a bean
 * selection smoke test.
 */
class MediaJobStoreSelectionTest {

    @Configuration
    @ComponentScan(
            basePackageClasses = {InMemoryMediaJobStore.class, FileBackedMediaJobStore.class},
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {InMemoryMediaJobStore.class, FileBackedMediaJobStore.class}
            )
    )
    static class JobStoreScanConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }

    @TempDir
    Path tmp;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(JobStoreScanConfig.class)
                .withPropertyValues("jarvis.media.job-store.dir=" + tmp);
    }

    @Test
    @DisplayName("no property set -> FileBackedMediaJobStore is the sole, effective default MediaJobStore bean")
    void defaultMode_activatesFileBackedStore() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MediaJobStore.class);
            assertThat(context.getBean(MediaJobStore.class)).isInstanceOf(FileBackedMediaJobStore.class);
        });
    }

    @Test
    @DisplayName("jarvis.media.job-store=memory -> InMemoryMediaJobStore is the sole MediaJobStore bean")
    void memoryMode_activatesInMemoryStore() {
        runner().withPropertyValues("jarvis.media.job-store=memory").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MediaJobStore.class);
            assertThat(context.getBean(MediaJobStore.class)).isInstanceOf(InMemoryMediaJobStore.class);
        });
    }

    @Test
    @DisplayName("jarvis.media.job-store=file -> FileBackedMediaJobStore is the sole MediaJobStore bean")
    void fileMode_activatesFileBackedStore() {
        runner().withPropertyValues("jarvis.media.job-store=file").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MediaJobStore.class);
            assertThat(context.getBean(MediaJobStore.class)).isInstanceOf(FileBackedMediaJobStore.class);
        });
    }

    @Test
    @DisplayName("a job saved through the default-wired store survives a fresh context (simulated restart)")
    void jobSavedThroughDefaultWiring_survivesReloadInAFreshContext() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        MediaJob job = MediaJob.created("j1", "u1", JobType.EXTRACT_AUDIO, "in.mkv", now);

        runner().run(context -> context.getBean(MediaJobStore.class).save(job));

        runner().run(context -> {
            Optional<MediaJob> found = context.getBean(MediaJobStore.class).findById("j1");
            assertThat(found).isPresent();
            assertThat(found.get().userId()).isEqualTo("u1");
            assertThat(found.get().status()).isEqualTo(JobStatus.CREATED);
        });
    }
}
