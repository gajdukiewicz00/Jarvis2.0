package org.jarvis.swarm.queue;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.role.coder.PendingPatchStore;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.support.SameThreadExecutorService;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStatus;
import org.jarvis.swarm.task.FileBackedAgentTaskStore;
import org.jarvis.swarm.task.InvalidTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CODER approval gate end to end: propose -> AWAITING_APPROVAL -> approve (applies
 * the staged patch to the sandbox, after a pre-apply snapshot) / reject (discards,
 * nothing is ever written). Rollback of an approved patch is covered here too since it
 * shares the same fixture.
 */
class AgentTaskApprovalGateTest {

    @TempDir
    Path tmp;

    @Test
    void approvalRequiredTaskStopsAtAwaitingApprovalWithNothingWritten() throws IOException {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), false, null, null, null, true);

        AgentTask pending = engine.taskService().getTask(created.taskId(), "u1");
        assertThat(pending.status()).isEqualTo(AgentTaskStatus.AWAITING_APPROVAL);
        assertThat(engine.taskService().resultOf(created.taskId()).awaitingApproval()).isTrue();

        try (var files = Files.list(Path.of(pending.sandboxPath()))) {
            assertThat(files.findAny()).isEmpty();
        }
    }

    @Test
    void approveAppliesTheStagedPatchToTheSandbox() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), false, null, null, null, true);

        AgentTask approved = engine.taskService().approve(created.taskId(), "u1");

        assertThat(approved.status()).isEqualTo(AgentTaskStatus.COMPLETED);
        Path sandboxDir = Path.of(approved.sandboxPath());
        assertThat(Files.exists(sandboxDir.resolve("PLAN.md"))).isTrue();
        assertThat(Files.exists(sandboxDir.resolve("DIFF.patch"))).isTrue();
        assertThat(approved.artifacts()).isNotEmpty();
        assertThat(engine.taskService().resultOf(created.taskId()).artifacts()).isNotEmpty();
    }

    @Test
    void rejectDiscardsTheProposalAndWritesNothing() throws IOException {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), false, null, null, null, true);

        AgentTask rejected = engine.taskService().reject(created.taskId(), "u1");

        assertThat(rejected.status()).isEqualTo(AgentTaskStatus.CANCELLED);
        assertThat(rejected.errorMessage()).isEqualTo("rejected_by_user");
        try (var files = Files.list(Path.of(rejected.sandboxPath()))) {
            assertThat(files.findAny()).isEmpty();
        }
    }

    @Test
    void approveThenRollbackRestoresTheEmptySandbox() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), false, null, null, null, true);
        AgentTask approved = engine.taskService().approve(created.taskId(), "u1");
        Path sandboxDir = Path.of(approved.sandboxPath());
        assertThat(Files.exists(sandboxDir.resolve("DIFF.patch"))).isTrue();

        engine.taskService().rollback(created.taskId(), "u1");

        assertThat(Files.exists(sandboxDir.resolve("DIFF.patch"))).isFalse();
        assertThat(Files.exists(sandboxDir.resolve("PLAN.md"))).isFalse();
    }

    @Test
    void approvingAnAlreadyAppliedTaskFailsBecauseTheProposalWasConsumed() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), false, null, null, null, true);
        engine.taskService().approve(created.taskId(), "u1");

        assertThatThrownBy(() -> engine.taskService().approve(created.taskId(), "u1"))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void approveOfATaskThatNeverRequestedApprovalIsRejected() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        // dryRun=true, approvalRequired=false: completes normally, never enters the gate.
        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), true, null, null, null, false);

        AgentTask done = engine.taskService().getTask(created.taskId(), "u1");
        assertThat(done.status()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThatThrownBy(() -> engine.taskService().approve(created.taskId(), "u1"))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void rejectOfATaskNotAwaitingApprovalIsRejected() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        AgentTask created = engine.taskService().submit("u1", AgentRole.DOCS, "doc it",
                Set.of(), true, null, null, null, false);

        assertThatThrownBy(() -> engine.taskService().reject(created.taskId(), "u1"))
                .isInstanceOf(InvalidTransitionException.class);
    }

    // --- durability: AWAITING_APPROVAL + its pending patch proposal across a "restart" ---
    //
    // These use FileBackedAgentTaskStore (the effective production default — see
    // AgentServiceApplication/FileBackedAgentTaskStore) and PendingPatchStore's own
    // write-through persistence, then rebuild BOTH from scratch pointed at the same
    // directory to simulate a fresh process picking up where a crashed/restarted one left
    // off, exactly like a pod restart reusing its emptyDir-backed /tmp.

    @Test
    void awaitingApprovalTaskAndItsPendingPatchSurviveAStoreReload() {
        Path taskStoreDir = tmp.resolve("tasks");
        FileBackedAgentTaskStore firstStore = new FileBackedAgentTaskStore(
                SwarmTestFactory.TEST_MAPPER, taskStoreDir.toString());
        PendingPatchStore firstPatches = new PendingPatchStore(
                SwarmTestFactory.TEST_MAPPER, taskStoreDir.toString());
        var firstRun = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES",
                new SameThreadExecutorService(), null, firstStore, firstPatches);

        AgentTask created = firstRun.taskService().submit("u1", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), false, null, null, null, true);
        assertThat(firstRun.taskService().getTask(created.taskId(), "u1").status())
                .isEqualTo(AgentTaskStatus.AWAITING_APPROVAL);
        assertThat(firstPatches.hasPending(created.taskId())).isTrue();

        // Simulate a process restart: brand new store + pending-patch instances (nothing
        // shared in memory with the run above), reloaded from the same directory.
        FileBackedAgentTaskStore reloadedStore = new FileBackedAgentTaskStore(
                SwarmTestFactory.TEST_MAPPER, taskStoreDir.toString());
        PendingPatchStore reloadedPatches = new PendingPatchStore(
                SwarmTestFactory.TEST_MAPPER, taskStoreDir.toString());

        AgentTask reloadedTask = reloadedStore.findById(created.taskId()).orElseThrow();
        assertThat(reloadedTask.status()).isEqualTo(AgentTaskStatus.AWAITING_APPROVAL);
        assertThat(reloadedPatches.hasPending(created.taskId())).isTrue();

        var restartedRun = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES",
                new SameThreadExecutorService(), null, reloadedStore, reloadedPatches);
        AgentTask approved = restartedRun.taskService().approve(created.taskId(), "u1");

        assertThat(approved.status()).isEqualTo(AgentTaskStatus.COMPLETED);
        Path sandboxDir = Path.of(approved.sandboxPath());
        assertThat(Files.exists(sandboxDir.resolve("PLAN.md"))).isTrue();
        assertThat(Files.exists(sandboxDir.resolve("DIFF.patch"))).isTrue();
        assertThat(approved.artifacts()).isNotEmpty();
        assertThat(reloadedPatches.hasPending(created.taskId())).isFalse();
    }

    @Test
    void rejectStillWorksAfterAwaitingApprovalTaskSurvivesAStoreReload() throws IOException {
        Path taskStoreDir = tmp.resolve("tasks");
        FileBackedAgentTaskStore firstStore = new FileBackedAgentTaskStore(
                SwarmTestFactory.TEST_MAPPER, taskStoreDir.toString());
        PendingPatchStore firstPatches = new PendingPatchStore(
                SwarmTestFactory.TEST_MAPPER, taskStoreDir.toString());
        var firstRun = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES",
                new SameThreadExecutorService(), null, firstStore, firstPatches);

        AgentTask created = firstRun.taskService().submit("u1", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), false, null, null, null, true);
        assertThat(firstRun.taskService().getTask(created.taskId(), "u1").status())
                .isEqualTo(AgentTaskStatus.AWAITING_APPROVAL);

        FileBackedAgentTaskStore reloadedStore = new FileBackedAgentTaskStore(
                SwarmTestFactory.TEST_MAPPER, taskStoreDir.toString());
        PendingPatchStore reloadedPatches = new PendingPatchStore(
                SwarmTestFactory.TEST_MAPPER, taskStoreDir.toString());
        var restartedRun = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES",
                new SameThreadExecutorService(), null, reloadedStore, reloadedPatches);

        AgentTask rejected = restartedRun.taskService().reject(created.taskId(), "u1");

        assertThat(rejected.status()).isEqualTo(AgentTaskStatus.CANCELLED);
        assertThat(rejected.errorMessage()).isEqualTo("rejected_by_user");
        assertThat(reloadedPatches.hasPending(created.taskId())).isFalse();
        try (var files = Files.list(Path.of(rejected.sandboxPath()))) {
            assertThat(files.findAny()).isEmpty();
        }
    }

    @Test
    void approvingAfterRestartFailsClearlyWhenThePendingPatchWasNeverPersisted() {
        // Degrade-clearly case: the task metadata survived (durable store) but its
        // pending-patch content did not (e.g. an operator wiped the pending-patches dir,
        // or is running an older PendingPatchStore build). approve() must fail loudly with
        // a clear, actionable message instead of silently fabricating a patch.
        Path taskStoreDir = tmp.resolve("tasks");
        FileBackedAgentTaskStore firstStore = new FileBackedAgentTaskStore(
                SwarmTestFactory.TEST_MAPPER, taskStoreDir.toString());
        PendingPatchStore firstPatches = new PendingPatchStore(
                SwarmTestFactory.TEST_MAPPER, taskStoreDir.toString());
        var firstRun = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES",
                new SameThreadExecutorService(), null, firstStore, firstPatches);

        AgentTask created = firstRun.taskService().submit("u1", AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), false, null, null, null, true);
        assertThat(firstRun.taskService().getTask(created.taskId(), "u1").status())
                .isEqualTo(AgentTaskStatus.AWAITING_APPROVAL);

        // A brand new pending-patch store pointed at an EMPTY directory: nothing to reload.
        PendingPatchStore emptyPatches = new PendingPatchStore(
                SwarmTestFactory.TEST_MAPPER, tmp.resolve("empty-patches").toString());
        FileBackedAgentTaskStore reloadedStore = new FileBackedAgentTaskStore(
                SwarmTestFactory.TEST_MAPPER, taskStoreDir.toString());
        var restartedRun = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES",
                new SameThreadExecutorService(), null, reloadedStore, emptyPatches);

        assertThatThrownBy(() -> restartedRun.taskService().approve(created.taskId(), "u1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(created.taskId());
    }
}
