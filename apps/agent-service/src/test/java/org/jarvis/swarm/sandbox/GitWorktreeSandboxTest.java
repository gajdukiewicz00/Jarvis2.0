package org.jarvis.swarm.sandbox;

import org.jarvis.swarm.config.SwarmProperties;
import org.jarvis.swarm.process.ProcessRunner;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link SandboxManager}'s git-worktree-backed sandbox option: a throwaway
 * checkout of a REAL git repository, path-traversal safe, cleaned up without touching
 * anything outside the worktree root.
 */
class GitWorktreeSandboxTest {

    @TempDir
    Path workspace;

    @TempDir
    Path repoDir;

    private SandboxManager manager() throws IOException, InterruptedException {
        initRepo(repoDir);
        SwarmProperties props = SwarmTestFactory.props(workspace, repoDir.toString());
        SandboxManager manager = new SandboxManager(props, new ProcessRunner());
        manager.init();
        return manager;
    }

    private void initRepo(Path dir) throws IOException, InterruptedException {
        run(dir, "git", "init", "-q");
        run(dir, "git", "config", "user.email", "swarm-test@example.com");
        run(dir, "git", "config", "user.name", "Swarm Test");
        Files.writeString(dir.resolve("README.md"), "hello\n");
        run(dir, "git", "add", ".");
        run(dir, "git", "commit", "-q", "-m", "init");
    }

    private void run(Path dir, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("test repo setup command failed: " + String.join(" ", command));
        }
    }

    @Test
    void createsAWorktreeCheckedOutFromHead() throws Exception {
        SandboxManager manager = manager();

        Sandbox sandbox = manager.createGitWorktree("task-wt-1");

        assertThat(Files.exists(sandbox.dir().resolve("README.md"))).isTrue();
        assertThat(sandbox.dir().startsWith(manager.root())).isTrue();
    }

    @Test
    void cleanupRemovesTheWorktreeDirectory() throws Exception {
        SandboxManager manager = manager();
        Sandbox sandbox = manager.createGitWorktree("task-wt-2");

        manager.cleanupGitWorktree(sandbox);

        assertThat(Files.exists(sandbox.dir())).isFalse();
    }

    @Test
    void cleanupOfAlreadyMissingWorktreeIsANoOp() throws Exception {
        SandboxManager manager = manager();
        Sandbox sandbox = manager.createGitWorktree("task-wt-missing");
        manager.cleanupGitWorktree(sandbox);

        manager.cleanupGitWorktree(sandbox); // second call: dir no longer exists

        assertThat(Files.exists(sandbox.dir())).isFalse();
    }

    @Test
    void disabledWhenNoGitRepoIsConfigured() {
        SwarmProperties props = SwarmTestFactory.props(workspace);
        SandboxManager manager = new SandboxManager(props, new ProcessRunner());
        manager.init();

        assertThat(manager.gitWorktreesEnabled()).isFalse();
        assertThatThrownBy(() -> manager.createGitWorktree("task-x")).isInstanceOf(SandboxException.class);
    }

    @Test
    void rejectsTraversalAndDuplicateWorktreeTaskIds() throws Exception {
        SandboxManager manager = manager();

        assertThatThrownBy(() -> manager.createGitWorktree("../evil")).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> manager.createGitWorktree("a/b")).isInstanceOf(SandboxException.class);

        manager.createGitWorktree("dup-task");
        assertThatThrownBy(() -> manager.createGitWorktree("dup-task")).isInstanceOf(SandboxException.class);
    }

    @Test
    void cleanupRefusesAPathOutsideTheWorktreeRoot() throws Exception {
        SandboxManager manager = manager();
        Sandbox notAWorktree = new Sandbox("fake", manager.root());

        assertThatThrownBy(() -> manager.cleanupGitWorktree(notAWorktree)).isInstanceOf(SandboxException.class);
    }
}
