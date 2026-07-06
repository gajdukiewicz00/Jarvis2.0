package org.jarvis.swarm.queue;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStatus;
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
}
