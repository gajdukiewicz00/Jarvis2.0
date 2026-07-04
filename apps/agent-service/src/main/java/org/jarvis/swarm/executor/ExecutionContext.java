package org.jarvis.swarm.executor;

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
 */
public final class ExecutionContext {

    private final AgentTask task;
    private final Sandbox sandbox;
    private final AgentActionGuard guard;
    private final CancellationToken token;
    private final PauseControl pause;

    public ExecutionContext(AgentTask task, Sandbox sandbox, AgentActionGuard guard,
                            CancellationToken token, PauseControl pause) {
        this.task = task;
        this.sandbox = sandbox;
        this.guard = guard;
        this.token = token;
        this.pause = pause;
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

    /** Cancellation + pause + mid-run panic checkpoint. */
    public void checkpoint() {
        token.throwIfCancelled();
        pause.awaitWhilePaused(token);
        guard.ensureNoPanic(task);
    }
}
