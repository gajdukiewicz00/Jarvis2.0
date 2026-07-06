package org.jarvis.swarm.queue;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleExecutor;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.support.BlockingRoleExecutor;
import org.jarvis.swarm.support.ForwardingAgentTaskStore;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStatus;
import org.jarvis.swarm.task.AgentTaskStore;
import org.jarvis.swarm.task.InMemoryAgentTaskStore;
import org.jarvis.swarm.task.TaskNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    void cancelOfAlreadyFailedTaskIsANoOpInsteadOfThrowing() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        engine.panic().engage("test", "drill", 1L);
        AgentTask created = engine.taskService().submit("u1", AgentRole.CODER, "build a thing",
                Set.of(), true, null, null);
        AgentTask failed = engine.taskService().getTask(created.taskId(), "u1");
        assertThat(failed.status()).isEqualTo(AgentTaskStatus.FAILED);

        // Before the fix, cancel() treated FAILED as non-terminal and attempted the
        // illegal FAILED -> CANCELLED transition, throwing InvalidTransitionException
        // (surfaced as an HTTP 409) instead of returning a harmless no-op.
        assertThat(engine.taskService().cancel(created.taskId(), "u1")).isFalse();
        AgentTask stillFailed = engine.taskService().getTask(created.taskId(), "u1");
        assertThat(stillFailed.status()).isEqualTo(AgentTaskStatus.FAILED);
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
    void submitWithSameIdempotencyKeyReturnsExistingTaskInsteadOfRunningAgain() {
        AtomicInteger calls = new AtomicInteger();
        RoleExecutor counting = new RoleExecutor() {
            @Override
            public AgentRole role() {
                return AgentRole.CODER;
            }

            @Override
            public RoleResult execute(ExecutionContext ctx) {
                calls.incrementAndGet();
                return RoleResult.success("done", null, List.of(), List.of(), List.of(), List.of());
            }
        };
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES",
                new org.jarvis.swarm.support.SameThreadExecutorService(), List.of(counting));

        AgentTask first = engine.taskService().submit("u1", AgentRole.CODER, "build a thing",
                Set.of(), false, null, null, "client-key-1");
        AgentTask second = engine.taskService().submit("u1", AgentRole.CODER, "a different goal",
                Set.of(), false, null, null, "client-key-1");

        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(calls.get()).isEqualTo(1);
        assertThat(engine.taskService().listTasks("u1")).hasSize(1);
    }

    @Test
    void submitWithDifferentIdempotencyKeysRunsTwice() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        AgentTask first = engine.taskService().submit("u1", AgentRole.CODER, "build a thing",
                Set.of(), true, null, null, "key-a");
        AgentTask second = engine.taskService().submit("u1", AgentRole.CODER, "build a thing",
                Set.of(), true, null, null, "key-b");

        assertThat(second.taskId()).isNotEqualTo(first.taskId());
        assertThat(engine.taskService().listTasks("u1")).hasSize(2);
    }

    @Test
    void submitWithSameIdempotencyKeyButDifferentUserRunsTwice() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        AgentTask first = engine.taskService().submit("u1", AgentRole.CODER, "build a thing",
                Set.of(), true, null, null, "shared-key");
        AgentTask second = engine.taskService().submit("u2", AgentRole.CODER, "build a thing",
                Set.of(), true, null, null, "shared-key");

        assertThat(second.taskId()).isNotEqualTo(first.taskId());
    }

    @Test
    void concurrentSubmitsWithSameIdempotencyKeyCreateOnlyOneTask() throws Exception {
        // Simulates two concurrent replays of the same idempotent request racing the
        // check-then-act in submit(): both must not be able to observe "not found" and
        // each create a new task. A decorator around the store deterministically forces
        // the first caller to be mid-check while the second caller is dispatched, so the
        // interleaving described in the bug report (both calls see Optional.empty()) is
        // reproduced exactly instead of relying on real thread-timing luck.
        CountDownLatch firstCallEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        // Counts down only if a second, distinct call reaches the lookup while the first
        // is still mid-check — i.e. only reachable pre-fix, since the fix's per-key lock
        // keeps a second caller blocked (never even reaching this method) until the first
        // fully completes.
        CountDownLatch secondCallReachedLookup = new CountDownLatch(1);
        AtomicInteger lookupCalls = new AtomicInteger();
        InMemoryAgentTaskStore delegate = new InMemoryAgentTaskStore();
        AgentTaskStore racingStore = new ForwardingAgentTaskStore(delegate) {
            @Override
            public Optional<AgentTask> findByIdempotencyKey(String userId, String idempotencyKey) {
                if (lookupCalls.getAndIncrement() == 0) {
                    firstCallEntered.countDown();
                    try {
                        releaseFirstCall.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // Real state at the moment this call started: nothing saved yet.
                    return Optional.empty();
                }
                secondCallReachedLookup.countDown();
                return delegate.findByIdempotencyKey(userId, idempotencyKey);
            }
        };

        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES",
                new org.jarvis.swarm.support.SameThreadExecutorService(), null, racingStore);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<AgentTask> callA = callers.submit(() -> engine.taskService().submit("u1", AgentRole.DOCS,
                    "goal", Set.of(), true, null, null, "race-key"));
            assertThat(firstCallEntered.await(2, TimeUnit.SECONDS)).isTrue();

            Future<AgentTask> callB = callers.submit(() -> engine.taskService().submit("u1", AgentRole.DOCS,
                    "goal", Set.of(), true, null, null, "race-key"));
            // Best-effort: give callB a window to reach its own lookup call *before* callA is
            // released, so an unguarded implementation reliably races both lookups against an
            // empty store instead of happening to interleave the other way. Under the fix,
            // callB blocks on the per-key lock instead, so this simply times out (expected)
            // and callA is released regardless.
            secondCallReachedLookup.await(500, TimeUnit.MILLISECONDS);
            releaseFirstCall.countDown();

            AgentTask taskA = callA.get(5, TimeUnit.SECONDS);
            AgentTask taskB = callB.get(5, TimeUnit.SECONDS);

            assertThat(taskB.taskId()).isEqualTo(taskA.taskId());
            assertThat(engine.taskService().listTasks("u1")).hasSize(1);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void submitWithoutIdempotencyKeyAlwaysRunsANewTask() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        AgentTask first = engine.taskService().submit("u1", AgentRole.CODER, "build a thing",
                Set.of(), true, null, null);
        AgentTask second = engine.taskService().submit("u1", AgentRole.CODER, "build a thing",
                Set.of(), true, null, null);

        assertThat(second.taskId()).isNotEqualTo(first.taskId());
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
