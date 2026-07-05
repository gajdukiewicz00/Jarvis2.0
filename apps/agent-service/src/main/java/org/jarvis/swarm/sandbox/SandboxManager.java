package org.jarvis.swarm.sandbox;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.swarm.config.SwarmProperties;
import org.jarvis.swarm.process.ProcessResult;
import org.jarvis.swarm.process.ProcessRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Creates and guards per-task sandboxes. Every agent file write goes through
 * {@link #resolve}, which rejects {@code ..} traversal, null bytes, absolute paths, and
 * any escape from the task's own sandbox directory. Cleanup is explicit and only ever
 * deletes inside the sandbox root — never the wider filesystem.
 *
 * <p>Also offers an OPTIONAL git-worktree-backed sandbox ({@link #createGitWorktree}) for
 * CODER/TESTER: a real, throwaway {@code git worktree} checked out on its own branch, so
 * those roles can diff/test against actual tracked files without ever touching the
 * shared working tree. Disabled unless {@code swarm.workspace.git-repo-dir} is set.</p>
 */
@Slf4j
@Component
public class SandboxManager {

    private static final int GIT_TIMEOUT_SECONDS = 30;

    private final Path root;
    private final Path worktreeRoot;
    private final Path repoDir;
    private final ProcessRunner processRunner;

    public SandboxManager(SwarmProperties props, ProcessRunner processRunner) {
        this.root = Path.of(props.workspace().dir()).toAbsolutePath().normalize();
        this.worktreeRoot = root.resolve("worktrees");
        String configuredRepo = props.workspace().gitRepoDir();
        this.repoDir = (configuredRepo == null || configuredRepo.isBlank())
                ? null
                : Path.of(configuredRepo).toAbsolutePath().normalize();
        this.processRunner = processRunner;
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

    /** Whether {@link #createGitWorktree} is usable (a git repo dir has been configured). */
    public boolean gitWorktreesEnabled() {
        return repoDir != null;
    }

    /** Create (or reuse) the isolated directory for a task. */
    public Sandbox create(String taskId) {
        requireValidTaskId(taskId);
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

    /**
     * Create a throwaway git worktree sandbox: a real, isolated checkout of the
     * configured repository, checked out on its own new {@code swarm/<taskId>} branch, so
     * CODER/TESTER can operate on genuinely tracked files (real {@code git diff}, real
     * build/test commands) without ever touching the actual working tree the swarm
     * itself runs from. Requires {@code swarm.workspace.git-repo-dir}; throws
     * {@link SandboxException} if unconfigured, if the taskId is invalid, or if the
     * worktree already exists.
     */
    public Sandbox createGitWorktree(String taskId) {
        if (!gitWorktreesEnabled()) {
            throw new SandboxException("git worktree sandboxes are not configured (swarm.workspace.git-repo-dir unset)");
        }
        requireValidTaskId(taskId);
        Path dir = worktreeRoot.resolve(taskId).toAbsolutePath().normalize();
        if (!dir.startsWith(worktreeRoot)) {
            throw new SandboxException("worktree escapes root");
        }
        if (Files.exists(dir)) {
            throw new SandboxException("worktree already exists for task: " + taskId);
        }
        try {
            Files.createDirectories(worktreeRoot);
        } catch (IOException e) {
            throw new SandboxException("cannot create worktree root: " + e.getMessage());
        }
        String branch = "swarm/" + taskId;
        ProcessResult result = runGit(List.of(
                "git", "worktree", "add", "-b", branch, dir.toString(), "HEAD"));
        if (!result.isSuccess()) {
            throw new SandboxException("git worktree add failed: " + firstLine(result.output()));
        }
        return new Sandbox(taskId, dir);
    }

    /**
     * Remove a git worktree sandbox: detaches it from git's own metadata (so `git
     * worktree list` doesn't accumulate stale entries) and deletes the checkout, ONLY
     * ever inside the worktree root — never the wider filesystem.
     */
    public void cleanupGitWorktree(Sandbox sandbox) {
        Path dir = sandbox.dir().toAbsolutePath().normalize();
        if (!dir.startsWith(worktreeRoot) || dir.equals(worktreeRoot)) {
            throw new SandboxException("refusing to clean up path outside worktree root: " + dir);
        }
        if (!Files.exists(dir)) {
            return;
        }
        if (gitWorktreesEnabled()) {
            runGit(List.of("git", "worktree", "remove", "--force", dir.toString()));
            runGit(List.of("git", "worktree", "prune"));
        }
        if (Files.exists(dir)) {
            deleteRecursively(dir);
        }
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
        deleteRecursively(dir);
    }

    private void requireValidTaskId(String taskId) {
        if (taskId == null || taskId.isBlank() || taskId.contains("..") || taskId.contains("/")) {
            throw new SandboxException("invalid task id for sandbox: " + taskId);
        }
    }

    private void deleteRecursively(Path dir) {
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

    private ProcessResult runGit(List<String> command) {
        try {
            return processRunner.run(command, repoDir, GIT_TIMEOUT_SECONDS);
        } catch (IOException e) {
            throw new SandboxException("git command failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException("git command interrupted");
        }
    }

    private String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "(no output)";
        }
        int newline = output.indexOf('\n');
        return newline >= 0 ? output.substring(0, newline) : output;
    }
}
