package org.jarvis.swarm.executor.role.coder;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holding pen for CODER patch proposals awaiting approval, keyed by taskId. Mirrors the
 * lifecycle of {@code AgentTaskService}'s own {@code tokens}/{@code pauses} maps:
 * populated when a task starts, consumed (or discarded) once its human decision arrives.
 *
 * <p>Every {@link #stage} writes the proposal through to its own JSON file immediately
 * (same write-through contract as {@code FileBackedAgentTaskStore}), and every {@link
 * #take}/{@link #discard} deletes that file — so a task that reaches AWAITING_APPROVAL
 * and then survives a process restart (see {@code FileBackedAgentTaskStore} /
 * {@code JpaAgentTaskStore}) keeps a working {@code approve}/{@code reject}: on
 * construction, any proposal file left behind by a prior process is reloaded into memory
 * before the service accepts traffic.</p>
 */
@Slf4j
@Component
public class PendingPatchStore {

    private final Path dir;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, PatchProposal> pending = new ConcurrentHashMap<>();

    public PendingPatchStore(
            ObjectMapper mapper,
            @Value("${jarvis.agent.task-store.dir:/tmp/jarvis-agent-tasks}") String taskStoreDir) {
        this.mapper = mapper;
        this.dir = Path.of(taskStoreDir).resolve("pending-patches").toAbsolutePath().normalize();
        init();
    }

    private void init() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create pending patch dir at " + dir, e);
        }
        load();
    }

    private void load() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                try {
                    PatchProposal proposal = mapper.readValue(file.toFile(), PatchProposal.class);
                    pending.put(taskIdFrom(file), proposal);
                } catch (IOException e) {
                    log.warn("Skipping unreadable pending patch file {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read pending patch dir at " + dir, e);
        }
        if (!pending.isEmpty()) {
            log.info("Reloaded {} pending patch proposal(s) from {}", pending.size(), dir);
        }
    }

    /** Stage a proposal for a task awaiting approval; persisted immediately so it survives a restart. */
    public void stage(String taskId, PatchProposal proposal) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file(taskId).toFile(), proposal);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist pending patch proposal for task " + taskId, e);
        }
        pending.put(taskId, proposal);
    }

    /** Remove and return the staged proposal for a task (consumed by an approve). */
    public Optional<PatchProposal> take(String taskId) {
        Optional<PatchProposal> proposal = Optional.ofNullable(pending.remove(taskId));
        deleteFile(taskId);
        return proposal;
    }

    /** Discard a staged proposal without applying it (consumed by a reject). */
    public void discard(String taskId) {
        pending.remove(taskId);
        deleteFile(taskId);
    }

    public boolean hasPending(String taskId) {
        return pending.containsKey(taskId);
    }

    private void deleteFile(String taskId) {
        try {
            Files.deleteIfExists(file(taskId));
        } catch (IOException e) {
            log.warn("Could not delete persisted pending patch file for {}: {}", taskId, e.getMessage());
        }
    }

    private Path file(String taskId) {
        return dir.resolve(taskId + ".json");
    }

    private String taskIdFrom(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".json".length());
    }
}
