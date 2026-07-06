package org.jarvis.swarm.executor;

import org.jarvis.swarm.executor.role.coder.PendingPatchStore;
import org.jarvis.swarm.permission.AgentActionGuard;
import org.jarvis.swarm.queue.CancellationToken;
import org.jarvis.swarm.queue.PauseControl;
import org.jarvis.swarm.sandbox.Sandbox;
import org.jarvis.swarm.task.AgentTask;

/**
 * Per-run context handed to a role executor. Carries the task, its sandbox (if any), the
 * permission guard, and cooperative cancel/pause signals. Executors must call
 * {@link #checkpoint()} between steps so cancellation, pause, and a mid-run panic all
 * take effect promptly.
 *
 * <p>{@code approvalRequired}/{@code pendingPatches} back the CODER approval gate: when
 * set, an executor that would otherwise apply a patch stages it in {@link
 * PendingPatchStore} and returns an {@code awaitingApproval} result instead — see {@code
 * CoderAgentExecutor} and {@code AgentTaskService#approve}/{@code #reject}.</p>
 */
public final class ExecutionContext {

    private final AgentTask task;
    private final Sandbox sandbox;
    private final AgentActionGuard guard;
    private final CancellationToken token;
    private final PauseControl pause;
    private final boolean approvalRequired;
    private final PendingPatchStore pendingPatches;

    public ExecutionContext(AgentTask task, Sandbox sandbox, AgentActionGuard guard,
                            CancellationToken token, PauseControl pause) {
        this(task, sandbox, guard, token, pause, false, null);
    }

    public ExecutionContext(AgentTask task, Sandbox sandbox, AgentActionGuard guard,
                            CancellationToken token, PauseControl pause,
                            boolean approvalRequired, PendingPatchStore pendingPatches) {
        this.task = task;
        this.sandbox = sandbox;
        this.guard = guard;
        this.token = token;
        this.pause = pause;
        this.approvalRequired = approvalRequired;
        this.pendingPatches = pendingPatches;
    }

    public AgentTask task() {
        return task;
    }

    public Sandbox sandbox() {
        return sandbox;
    }

    public AgentActionGuard guard() {
        return guard;
    }

    public boolean dryRun() {
        return task.dryRun();
    }

    public boolean approvalRequired() {
        return approvalRequired;
    }

    public PendingPatchStore pendingPatches() {
        return pendingPatches;
    }

    /** Cancellation + pause + mid-run panic checkpoint. */
    public void checkpoint() {
        token.throwIfCancelled();
        pause.awaitWhilePaused(token);
        guard.ensureNoPanic(task);
    }
}
