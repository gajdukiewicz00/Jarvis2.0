package org.jarvis.swarm.queue;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleExecutor;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.support.BlockingRoleExecutor;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStatus;
import org.jarvis.swarm.task.TaskNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentTaskServiceTest {

    @TempDir
    Path tmp;

    @Test
    void coderDryRunRunsToCompletedWithProposedActions() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "build a thing",
                Set.of(), true, null, null);
        AgentTask done = engine.taskService().getTask(created.taskId(), "u1");
        assertThat(done.status()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThat(engine.taskService().resultOf(created.taskId()).proposedActions()).isNotEmpty();
    }

    @Test
    void panicBlocksTaskStart() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        engine.panic().engage("test", "drill", 1L);
        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "build a thing",
                Set.of(), true, null, null);
        AgentTask blocked = engine.taskService().getTask(created.taskId(), "u1");
        assertThat(blocked.status()).isEqualTo(AgentTaskStatus.FAILED);
        assertThat(blocked.errorMessage()).isEqualTo("panic_engaged");
    }

    @Test
    void runShellTaskRejectedWhenSystemPolicyDeniesIt() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES"); // no RUN_SHELL in system policy
        AgentTask created = engine.taskService().submit("u1", AgentRole.TESTER, "run: echo hi",
                Set.of(ToolPermission.RUN_SHELL), false, null, null);
        AgentTask rejected = engine.taskService().getTask(created.taskId(), "u1");
        assertThat(rejected.status()).isEqualTo(AgentTaskStatus.FAILED);
        assertThat(rejected.errorMessage()).contains("RUN_SHELL");
    }

    @Test
    void tasksAreScopedToOwner() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        AgentTask created = engine.taskService().submit("owner", AgentRole.DOCS, "doc", Set.of(), true, null, null);
        assertThatThrownBy(() -> engine.taskService().getTask(created.taskId(), "intruder"))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void cancelOfCompletedTaskReturnsFalse() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        AgentTask created = engine.taskService().submit("u1", AgentRole.DOCS, "doc", Set.of(), true, null, null);
        assertThat(engine.taskService().cancel(created.taskId(), "u1")).isFalse();
    }

    @Test
    void cancelStopsRunningTask() throws Exception {
        ExecutorService async = Executors.newSingleThreadExecutor();
        BlockingRoleExecutor blocking = new BlockingRoleExecutor(AgentRole.DOCS);
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES", async, List.of(blocking));

        AgentTask created = engine.taskService().submit("u1", AgentRole.DOCS, "long doc", Set.of(), false, null, null);
        assertThat(blocking.started().await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(engine.taskService().cancel(created.taskId(), "u1")).isTrue();
        AgentTask result = awaitTerminal(engine, created.taskId());
        assertThat(result.status()).isEqualTo(AgentTaskStatus.CANCELLED);
        async.shutdownNow();
    }

    @Test
    void pauseThenResumeRunsToCompletion() throws Exception {
        ExecutorService async = Executors.newSingleThreadExecutor();
        BlockingRoleExecutor blocking = new BlockingRoleExecutor(AgentRole.DOCS);
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES", async, List.of(blocking));

        AgentTask created = engine.taskService().submit("u1", AgentRole.DOCS, "long doc", Set.of(), false, null, null);
        assertThat(blocking.started().await(2, TimeUnit.SECONDS)).isTrue();

        AgentTask paused = engine.taskService().pause(created.taskId(), "u1");
        assertThat(paused.status()).isEqualTo(AgentTaskStatus.PAUSED);

        engine.taskService().resume(created.taskId(), "u1");
        blocking.release();
        AgentTask result = awaitTerminal(engine, created.taskId());
        assertThat(result.status()).isEqualTo(AgentTaskStatus.COMPLETED);
        async.shutdownNow();
    }

    @Test
    void submitRejectsBlankGoal() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        assertThatThrownBy(() -> engine.taskService().submit("u1", AgentRole.CODER, "   ",
                Set.of(), true, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitMarksTaskFailedWhenQueueRejectsWork() {
        ExecutorService rejecting = mock(ExecutorService.class);
        when(rejecting.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("full"));
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES", rejecting);

        assertThatThrownBy(() -> engine.taskService().submit("u1", AgentRole.CODER, "build a thing",
                Set.of(), true, null, null))
                .isInstanceOf(RejectedExecutionException.class);

        AgentTask saved = engine.taskService().listTasks("u1").get(0);
        assertThat(saved.status()).isEqualTo(AgentTaskStatus.FAILED);
        assertThat(saved.errorMessage()).contains("saturated");
    }

    @Test
    void retriesOnFailureUntilRoleBudgetThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        RoleExecutor flaky = new RoleExecutor() {
            @Override
            public AgentRole role() {
                return AgentRole.CODER;
            }

            @Override
            public RoleResult execute(ExecutionContext ctx) {
                if (calls.getAndIncrement() == 0) {
                    return RoleResult.failure("transient failure", List.of());
                }
                return RoleResult.success("second attempt worked", null, List.of(), List.of(), List.of(), List.of());
            }
        };
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES",
                new org.jarvis.swarm.support.SameThreadExecutorService(), List.of(flaky));

        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "flaky build",
                Set.of(), false, null, null);
        AgentTask done = engine.taskService().getTask(created.taskId(), "u1");

        assertThat(calls.get()).isEqualTo(2);
        assertThat(done.status()).isEqualTo(AgentTaskStatus.COMPLETED);
    }

    private AgentTask awaitTerminal(SwarmTestFactory.Engine engine, String id) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            AgentTask task = engine.taskService().getTask(id, "u1");
            if (task.status().isTerminal()) {
                return task;
            }
            Thread.sleep(20);
        }
        return engine.taskService().getTask(id, "u1");
    }
}
