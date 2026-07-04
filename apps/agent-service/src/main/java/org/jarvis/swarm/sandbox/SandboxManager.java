package org.jarvis.swarm.sandbox;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.swarm.config.SwarmProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Creates and guards per-task sandboxes. Every agent file write goes through
 * {@link #resolve}, which rejects {@code ..} traversal, null bytes, absolute paths, and
 * any escape from the task's own sandbox directory. Cleanup is explicit and only ever
 * deletes inside the sandbox root — never the wider filesystem.
 */
@Slf4j
@Component
public class SandboxManager {

    private final Path root;

    public SandboxManager(SwarmProperties props) {
        this.root = Path.of(props.workspace().dir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(root);
            log.info("Agent sandbox root ready at {}", root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create sandbox root at " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    /** Create (or reuse) the isolated directory for a task. */
    public Sandbox create(String taskId) {
        if (taskId == null || taskId.isBlank() || taskId.contains("..") || taskId.contains("/")) {
            throw new SandboxException("invalid task id for sandbox: " + taskId);
        }
        Path dir = root.resolve(taskId).toAbsolutePath().normalize();
        if (!dir.startsWith(root)) {
            throw new SandboxException("sandbox escapes root");
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new SandboxException("cannot create sandbox: " + e.getMessage());
        }
        return new Sandbox(taskId, dir);
    }

    /** Resolve a relative artifact name strictly inside the sandbox. */
    public Path resolve(Sandbox sandbox, String relativeName) {
        if (relativeName == null || relativeName.isBlank()) {
            throw new SandboxException("artifact name must not be blank");
        }
        if (relativeName.indexOf('\0') >= 0) {
            throw new SandboxException("artifact name contains a null byte");
        }
        for (String segment : relativeName.split("[/\\\\]")) {
            if (segment.equals("..")) {
                throw new SandboxException("artifact name contains traversal: " + relativeName);
            }
        }
        if (Path.of(relativeName).isAbsolute()) {
            throw new SandboxException("artifact name must be relative: " + relativeName);
        }
        Path resolved = sandbox.dir().resolve(relativeName).toAbsolutePath().normalize();
        if (!resolved.startsWith(sandbox.dir())) {
            throw new SandboxException("artifact path escapes sandbox: " + relativeName);
        }
        return resolved;
    }

    /** Write a text artifact inside the sandbox; returns its absolute path. */
    public Path writeFile(Sandbox sandbox, String relativeName, String content) {
        Path target = resolve(sandbox, relativeName);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SandboxException("cannot write sandbox file: " + e.getMessage());
        }
        return target;
    }

    public long sizeOrZero(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Safe explicit cleanup: deletes the sandbox directory and its contents, but ONLY
     * if it is genuinely inside the sandbox root. Never touches anything outside.
     */
    public void cleanup(Sandbox sandbox) {
        Path dir = sandbox.dir().toAbsolutePath().normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            throw new SandboxException("refusing to clean up path outside sandbox root: " + dir);
        }
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("sandbox cleanup could not delete {}: {}", p.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new SandboxException("sandbox cleanup failed: " + e.getMessage());
        }
    }
}
