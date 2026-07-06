package org.jarvis.swarm.queue;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.audit.AgentAudit;
import org.jarvis.swarm.audit.SwarmMetrics;
import org.jarvis.swarm.config.AsyncConfig;
import org.jarvis.swarm.config.SwarmProperties;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleExecutorRegistry;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.permission.AgentActionGuard;
import org.jarvis.swarm.permission.AgentPermissionResolver;
import org.jarvis.swarm.permission.PanicEngagedException;
import org.jarvis.swarm.permission.PermissionDeniedException;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.role.RoleDefinition;
import org.jarvis.swarm.sandbox.Sandbox;
import org.jarvis.swarm.sandbox.SandboxManager;
import org.jarvis.swarm.role.RoleCatalog;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStore;
import org.jarvis.swarm.task.TaskNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * Lifecycle owner for agent tasks: create → queue → (pause/resume) → run → terminal.
 *
 * <p>Tasks run on a bounded worker pool; request threads never block. Before a task
 * starts and at every executor checkpoint, the global panic switch is honored. Tasks are
 * cancellable (cooperative token + interrupt) and pausable (cooperative gate). A failed
 * task may retry within its role's budget.</p>
 */
@Slf4j
@Service
public class AgentTaskService {

    private final AgentTaskStore store;
    private final ExecutorService executor;
    private final Clock clock;
    private final RoleCatalog catalog;
    private final AgentPermissionResolver resolver;
    private final SandboxManager sandboxManager;
    private final AgentActionGuard guard;
    private final RoleExecutorRegistry executors;
    private final AgentAudit audit;
    private final SwarmMetrics metrics;

    private final ConcurrentHashMap<String, CancellationToken> tokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PauseControl> pauses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RoleResult> results = new ConcurrentHashMap<>();

    public AgentTaskService(AgentTaskStore store,
                            @Qualifier(AsyncConfig.AGENT_TASK_EXECUTOR) ExecutorService executor,
                            Clock clock, RoleCatalog catalog, AgentPermissionResolver resolver,
                            SandboxManager sandboxManager, AgentActionGuard guard,
                            RoleExecutorRegistry executors, AgentAudit audit, SwarmMetrics metrics,
                            SwarmProperties props) {
        this.store = store;
        this.executor = executor;
        this.clock = clock;
        this.catalog = catalog;
        this.resolver = resolver;
        this.sandboxManager = sandboxManager;
        this.guard = guard;
        this.executors = executors;
        this.audit = audit;
        this.metrics = metrics;
    }

    /** Create + queue a task. Returns immediately with the QUEUED task. */
    public AgentTask submit(String userId, AgentRole role, String goal, Set<ToolPermission> requested,
                            boolean dryRun, String swarmId, String correlationId) {
        return submit(userId, role, goal, requested, dryRun, swarmId, correlationId, null);
    }

    /**
     * Create + queue a task, honoring an optional client idempotency key: a repeated
     * submit for the same user + key returns the ALREADY-existing task (whatever state
     * it has reached) instead of starting a second run. The key is not validated against
     * the goal/role/permissions of the original request — a mismatched replay simply
     * gets the original task back, mirroring the "return the existing result" contract
     * rather than treating it as a conflict.
     */
    public AgentTask submit(String userId, AgentRole role, String goal, Set<ToolPermission> requested,
                            boolean dryRun, String swarmId, String correlationId, String idempotencyKey) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal is required");
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<AgentTask> existing = store.findByIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                metrics.replayed();
                return existing.get();
            }
        }
        RoleDefinition def = catalog.definition(role);
        String taskId = UUID.randomUUID().toString();
        String corr = (correlationId == null || correlationId.isBlank()) ? UUID.randomUUID().toString() : correlationId;
        Set<ToolPermission> granted = resolver.resolveGrants(role, requested);

        AgentTask task = AgentTask.created(taskId, userId, role, goal, requested, dryRun,
                        def.maxRetries(), corr, swarmId, clock.instant())
                .withGranted(granted)
                .withIdempotencyKey(idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey);
        store.save(task);
        audit.lifecycle(taskId, corr, role.name(), "CREATED");

        task = task.queued(clock.instant());
        store.save(task);
        audit.lifecycle(taskId, corr, role.name(), "QUEUED");

        tokens.put(taskId, new CancellationToken());
        pauses.put(taskId, new PauseControl());
        try {
            Future<?> future = executor.submit(() -> run(taskId));
            futures.put(taskId, future);
        } catch (RejectedExecutionException rejected) {
            tokens.remove(taskId);
            pauses.remove(taskId);
            store.save(task.failed("agent queue saturated; retry later", clock.instant()));
            throw rejected;
        }
        metrics.created();
        return task;
    }

    public AgentTask getTask(String id, String userId) {
        return store.findById(id)
                .filter(t -> ownedBy(t, userId))
                .orElseThrow(() -> new TaskNotFoundException(id));
    }

    public RoleResult resultOf(String id) {
        return results.get(id);
    }

    /** Drops the cached {@link RoleResult} for a task removed by retention cleanup. */
    public void evictResult(String taskId) {
        results.remove(taskId);
    }

    public List<AgentTask> listTasks(String userId) {
        return store.findByUser(userId);
    }

    public boolean cancel(String id, String userId) {
        AgentTask task = getTask(id, userId);
        if (task.status().isTerminal()) {
            return false;
        }
        CancellationToken token = tokens.get(id);
        if (token != null) {
            token.cancel();
        }
        PauseControl pause = pauses.get(id);
        if (pause != null) {
            pause.resume(); // wake a paused worker so it can observe cancellation
        }
        Future<?> future = futures.get(id);
        if (future != null) {
            future.cancel(true);
        }
        store.findById(id).ifPresent(latest -> {
            if (!latest.status().isTerminal()) {
                store.save(latest.cancelled(clock.instant()));
                metrics.cancelled();
                audit.lifecycle(id, latest.correlationId(), latest.role().name(), "CANCELLED");
            }
        });
        return true;
    }

    public AgentTask pause(String id, String userId) {
        AgentTask task = getTask(id, userId);
        AgentTask paused = task.paused(clock.instant()); // validates transition (409 if illegal)
        store.save(paused);
        PauseControl pause = pauses.get(id);
        if (pause != null) {
            pause.pause();
        }
        audit.lifecycle(id, task.correlationId(), task.role().name(), "PAUSED");
        return paused;
    }

    public AgentTask resume(String id, String userId) {
        AgentTask task = getTask(id, userId);
        AgentTask resumed = task.resumed(clock.instant()); // validates transition (409 if illegal)
        store.save(resumed);
        PauseControl pause = pauses.get(id);
        if (pause != null) {
            pause.resume();
        }
        audit.lifecycle(id, task.correlationId(), task.role().name(), "RESUMED");
        return resumed;
    }

    // --- worker ---

    private void run(String taskId) {
        CancellationToken token = tokens.getOrDefault(taskId, new CancellationToken());
        PauseControl pause = pauses.getOrDefault(taskId, new PauseControl());
        AgentTask task = store.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        try {
            if (token.isCancelled()) {
                finishCancelled(taskId);
                return;
            }
            // Panic blocks task start outright.
            if (guard.panicEngaged()) {
                audit.panicBlocked(taskId, task.correlationId(), task.role().name());
                store.save(reload(taskId).failed("panic_engaged", clock.instant()));
                metrics.failed();
                return;
            }
            // Honor a pause requested before the task got a worker.
            pause.awaitWhilePaused(token);

            RoleDefinition def = catalog.definition(task.role());
            Sandbox sandbox = null;
            if (def.sandboxRequired()) {
                sandbox = sandboxManager.create(taskId);
                store.save(reload(taskId).withSandbox(sandbox.path()));
            }

            AgentTask runningTask = reload(taskId).running(clock.instant());
            store.save(runningTask);
            metrics.running();
            audit.lifecycle(taskId, runningTask.correlationId(), runningTask.role().name(), "RUNNING");

            ExecutionContext ctx = new ExecutionContext(runningTask, sandbox, guard, token, pause);
            RoleResult result = runWithRetries(ctx, def.maxRetries());
            results.put(taskId, result);

            AgentTask current = reload(taskId);
            if (result.success()) {
                store.save(current.completed(result.summary(), result.artifacts(), result.risks(), clock.instant()));
                metrics.completed();
                audit.lifecycle(taskId, current.correlationId(), current.role().name(), "COMPLETED");
            } else {
                store.save(current.failed(result.summary(), clock.instant()));
                metrics.failed();
                audit.lifecycle(taskId, current.correlationId(), current.role().name(), "FAILED");
            }
            recordDuration(current);
        } catch (TaskCancelledException cancelled) {
            finishCancelled(taskId);
        } catch (PermissionDeniedException | PanicEngagedException denied) {
            store.save(reload(taskId).failed(denied.getMessage(), clock.instant()));
            metrics.failed();
        } catch (Exception e) {
            log.warn("Agent task {} failed: {}", taskId, e.toString());
            store.save(reload(taskId).failed(safeMessage(e), clock.instant()));
            metrics.failed();
        } finally {
            tokens.remove(taskId);
            pauses.remove(taskId);
            futures.remove(taskId);
        }
    }

    private RoleResult runWithRetries(ExecutionContext ctx, int maxRetries) {
        int attempt = 0;
        RoleResult result;
        while (true) {
            ctx.checkpoint();
            result = executors.forRole(ctx.task().role()).execute(ctx);
            if (result.success() || attempt >= maxRetries) {
                return result;
            }
            attempt++;
            log.info("Retrying agent task {} (attempt {} of {})", ctx.task().taskId(), attempt, maxRetries);
        }
    }

    private void finishCancelled(String taskId) {
        AgentTask current = reload(taskId);
        if (current != null && !current.status().isTerminal()) {
            store.save(current.cancelled(clock.instant()));
            metrics.cancelled();
        }
    }

    private void recordDuration(AgentTask task) {
        if (task.startedAt() != null) {
            metrics.recordDuration(Duration.between(task.startedAt(), clock.instant()));
        }
    }

    private AgentTask reload(String taskId) {
        return store.findById(taskId).orElse(null);
    }

    private boolean ownedBy(AgentTask task, String userId) {
        return userId != null && userId.equals(task.userId());
    }

    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }
}
