package org.jarvis.swarm.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code AgentTaskStore} bean-selection wiring itself (independent of the full
 * Spring Boot app context in {@code AgentServiceIntegrationTest}): with {@code
 * jarvis.agent.task-store} left UNSET — the real-world "operator forgot to configure it"
 * case — the durable {@link FileBackedAgentTaskStore} must be the one that wins. Losing
 * every queued or AWAITING_APPROVAL task on a pod restart is not an acceptable default for
 * a real deployment (see class javadoc on both stores). {@code memory} opts back into the
 * ephemeral store explicitly, e.g. for fast local dev.
 */
class AgentTaskStoreDefaultSelectionTest {

    @TempDir
    Path tmp;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(ObjectMapper.class)
                .withUserConfiguration(InMemoryAgentTaskStore.class, FileBackedAgentTaskStore.class)
                .withPropertyValues("jarvis.agent.task-store.dir=" + tmp);
    }

    @Test
    void defaultsToTheDurableFileBackedStoreWhenThePropertyIsUnset() {
        runner().run(ctx -> {
            assertThat(ctx).hasSingleBean(AgentTaskStore.class);
            assertThat(ctx).hasSingleBean(FileBackedAgentTaskStore.class);
            assertThat(ctx).doesNotHaveBean(InMemoryAgentTaskStore.class);
        });
    }

    @Test
    void explicitFilePropertySelectsTheFileBackedStore() {
        runner().withPropertyValues("jarvis.agent.task-store=file").run(ctx -> {
            assertThat(ctx).hasSingleBean(FileBackedAgentTaskStore.class);
            assertThat(ctx).doesNotHaveBean(InMemoryAgentTaskStore.class);
        });
    }

    @Test
    void explicitMemoryPropertyOptsIntoTheEphemeralStoreInstead() {
        runner().withPropertyValues("jarvis.agent.task-store=memory").run(ctx -> {
            assertThat(ctx).hasSingleBean(InMemoryAgentTaskStore.class);
            assertThat(ctx).doesNotHaveBean(FileBackedAgentTaskStore.class);
        });
    }

    @Test
    void explicitPostgresPropertySelectsNeitherLocalStore() {
        // The real postgres bean lives behind PostgresTaskStoreAutoConfiguration + JPA
        // infrastructure this slice test doesn't stand up; what matters here is that
        // neither local (memory/file) store activates once postgres is explicitly chosen.
        runner().withPropertyValues("jarvis.agent.task-store=postgres").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(InMemoryAgentTaskStore.class);
            assertThat(ctx).doesNotHaveBean(FileBackedAgentTaskStore.class);
        });
    }
}
