package org.jarvis.swarm.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed task store: persists every {@link AgentTask} as its own JSON file so
 * tasks survive a pod restart, without introducing a database. All tasks are loaded
 * into an in-memory cache on construction and every {@link #save} writes through to
 * disk immediately. This is the EFFECTIVE DEFAULT: it activates whenever {@code
 * jarvis.agent.task-store} is left unset (real deployments must not silently lose queued
 * or AWAITING_APPROVAL work) as well as when it is explicitly set to {@code file}. Set it
 * to {@code memory} to opt back into the ephemeral {@link InMemoryAgentTaskStore} (fast
 * local dev only), or to {@code postgres} for the JPA-backed store.
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "jarvis.agent.task-store", havingValue = "file", matchIfMissing = true)
public class FileBackedAgentTaskStore implements AgentTaskStore {

    private final Path dir;
    private final ObjectMapper mapper;
    private final Map<String, AgentTask> tasks = new ConcurrentHashMap<>();

    public FileBackedAgentTaskStore(
            ObjectMapper mapper,
            @Value("${jarvis.agent.task-store.dir:/tmp/jarvis-agent-tasks}") String dir) {
        this.mapper = mapper;
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        init();
    }

    private void init() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create agent task store dir at " + dir, e);
        }
        load();
    }

    private void load() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                try {
                    AgentTask task = mapper.readValue(file.toFile(), AgentTask.class);
                    tasks.put(task.taskId(), task);
                } catch (IOException e) {
                    log.warn("Skipping unreadable agent task file {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read agent task store dir at " + dir, e);
        }
        log.info("Loaded {} agent task(s) from {}", tasks.size(), dir);
    }

    @Override
    public AgentTask save(AgentTask task) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file(task.taskId()).toFile(), task);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist agent task " + task.taskId(), e);
        }
        tasks.put(task.taskId(), task);
        return task;
    }

    @Override
    public Optional<AgentTask> findById(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public List<AgentTask> findByUser(String userId) {
        return tasks.values().stream()
                .filter(t -> t.userId() != null && t.userId().equals(userId))
                .sorted(Comparator.comparing(AgentTask::createdAt).reversed())
                .toList();
    }

    @Override
    public List<AgentTask> findBySwarm(String swarmId) {
        return tasks.values().stream()
                .filter(t -> swarmId.equals(t.swarmId()))
                .sorted(Comparator.comparing(AgentTask::createdAt))
                .toList();
    }

    @Override
    public Optional<AgentTask> findByIdempotencyKey(String userId, String idempotencyKey) {
        return tasks.values().stream()
                .filter(t -> userId.equals(t.userId()) && idempotencyKey.equals(t.idempotencyKey()))
                .findFirst();
    }

    @Override
    public List<AgentTask> findByStatuses(Set<AgentTaskStatus> statuses) {
        return tasks.values().stream()
                .filter(t -> statuses.contains(t.status()))
                .toList();
    }

    @Override
    public boolean deleteById(String id) {
        AgentTask removed = tasks.remove(id);
        if (removed == null) {
            return false;
        }
        try {
            Files.deleteIfExists(file(id));
        } catch (IOException e) {
            log.warn("Could not delete persisted agent task file for {}: {}", id, e.getMessage());
        }
        return true;
    }

    private Path file(String taskId) {
        return dir.resolve(taskId + ".json");
    }
}
