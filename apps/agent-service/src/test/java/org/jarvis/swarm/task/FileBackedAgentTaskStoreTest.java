package org.jarvis.swarm.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.role.AgentRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FileBackedAgentTaskStoreTest {

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void savedTaskSurvivesReloadInANewInstance() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        AgentTask task = AgentTask.created("t1", "u1", AgentRole.CODER, "build a thing",
                Set.of(ToolPermission.READ_FILES), false, 1, "corr-1", "swarm-1", now);

        FileBackedAgentTaskStore first = new FileBackedAgentTaskStore(mapper, tmp.toString());
        first.save(task);

        FileBackedAgentTaskStore reloaded = new FileBackedAgentTaskStore(mapper, tmp.toString());
        Optional<AgentTask> found = reloaded.findById("t1");

        assertThat(found).isPresent();
        assertThat(found.get().taskId()).isEqualTo("t1");
        assertThat(found.get().userId()).isEqualTo("u1");
        assertThat(found.get().role()).isEqualTo(AgentRole.CODER);
        assertThat(found.get().status()).isEqualTo(AgentTaskStatus.CREATED);
        assertThat(found.get().permissionsRequested()).containsExactly(ToolPermission.READ_FILES);
        assertThat(found.get().createdAt()).isEqualTo(now);
    }

    @Test
    void reloadedStoreSupportsFindByUserAndFindBySwarm() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        AgentTask taskA = AgentTask.created("t1", "u1", AgentRole.CODER, "goal a",
                Set.of(), false, 1, "corr-1", "swarm-1", now);
        AgentTask taskB = AgentTask.created("t2", "u1", AgentRole.TESTER, "goal b",
                Set.of(), false, 1, "corr-2", "swarm-1", now.plusSeconds(1));
        AgentTask taskC = AgentTask.created("t3", "u2", AgentRole.DOCS, "goal c",
                Set.of(), false, 1, "corr-3", "swarm-2", now.plusSeconds(2));

        FileBackedAgentTaskStore first = new FileBackedAgentTaskStore(mapper, tmp.toString());
        first.save(taskA);
        first.save(taskB);
        first.save(taskC);

        FileBackedAgentTaskStore reloaded = new FileBackedAgentTaskStore(mapper, tmp.toString());

        assertThat(reloaded.findByUser("u1")).extracting(AgentTask::taskId)
                .containsExactlyInAnyOrder("t1", "t2");
        assertThat(reloaded.findBySwarm("swarm-1")).extracting(AgentTask::taskId)
                .containsExactly("t1", "t2");
        assertThat(reloaded.findById("t3")).isPresent();
    }

    @Test
    void updatingATaskOverwritesThePersistedFile() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        AgentTask task = AgentTask.created("t1", "u1", AgentRole.CODER, "build a thing",
                Set.of(), false, 1, "corr-1", "swarm-1", now);

        FileBackedAgentTaskStore first = new FileBackedAgentTaskStore(mapper, tmp.toString());
        first.save(task);
        first.save(task.queued(now).running(now));

        FileBackedAgentTaskStore reloaded = new FileBackedAgentTaskStore(mapper, tmp.toString());
        assertThat(reloaded.findById("t1").orElseThrow().status()).isEqualTo(AgentTaskStatus.RUNNING);
    }
}
