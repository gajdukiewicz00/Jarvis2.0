package org.jarvis.swarm.support;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jarvis.common.safety.SystemPanicState;
import org.jarvis.common.safety.ToolPermissionPolicy;
import org.jarvis.swarm.audit.AgentAudit;
import org.jarvis.swarm.audit.SwarmMetrics;
import org.jarvis.swarm.config.SwarmProperties;
import org.jarvis.swarm.executor.RoleExecutor;
import org.jarvis.swarm.executor.RoleExecutorRegistry;
import org.jarvis.swarm.executor.role.CoderAgentExecutor;
import org.jarvis.swarm.executor.role.DocsAgentExecutor;
import org.jarvis.swarm.executor.role.FinanceAgentExecutor;
import org.jarvis.swarm.executor.role.MediaAgentExecutor;
import org.jarvis.swarm.executor.role.ResearchAgentExecutor;
import org.jarvis.swarm.executor.role.SecurityAgentExecutor;
import org.jarvis.swarm.executor.role.TesterAgentExecutor;
import org.jarvis.swarm.executor.role.coder.PendingPatchStore;
import org.jarvis.swarm.permission.AgentActionGuard;
import org.jarvis.swarm.permission.AgentPermissionResolver;
import org.jarvis.swarm.process.OutputSanitizer;
import org.jarvis.swarm.process.ProcessRunner;
import org.jarvis.swarm.queue.AgentTaskService;
import org.jarvis.swarm.role.RoleCatalog;
import org.jarvis.swarm.run.SwarmCoordinator;
import org.jarvis.swarm.sandbox.SandboxManager;
import org.jarvis.swarm.task.AgentTaskStore;
import org.jarvis.swarm.task.InMemoryAgentTaskStore;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.permission.AgentActionGuard;
import org.jarvis.swarm.queue.CancellationToken;
import org.jarvis.swarm.queue.PauseControl;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.sandbox.Sandbox;
import org.jarvis.swarm.task.AgentTask;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** Builds a fully-wired, in-memory swarm engine for deterministic unit tests. */
public final class SwarmTestFactory {

    private SwarmTestFactory() {
    }

    /** Build a task with explicit granted permissions (bypasses the resolver for executor tests). */
    public static AgentTask task(AgentRole role, String goal, Set<ToolPermission> requested,
                                 Set<ToolPermission> granted, boolean dryRun) {
        return AgentTask.created(UUID.randomUUID().toString(), "u1", role, goal,
                requested, dryRun, 1, "corr-test", null, Instant.now()).withGranted(granted);
    }

    public static ExecutionContext context(AgentTask task, Sandbox sandbox, AgentActionGuard guard) {
        return new ExecutionContext(task, sandbox, guard, new CancellationToken(), new PauseControl());
    }

    public record Engine(
            AgentTaskService taskService,
            AgentTaskStore store,
            SystemPanicState panic,
            SandboxManager sandbox,
            RoleCatalog catalog,
            AgentPermissionResolver resolver,
            AgentActionGuard guard,
            SwarmCoordinator coordinator,
            SwarmProperties props) {}

    public static SwarmProperties props(Path sandboxDir) {
        return props(sandboxDir, "");
    }

    /** Same as {@link #props(Path)} but with a git repo dir wired for worktree-sandbox tests. */
    public static SwarmProperties props(Path sandboxDir, String gitRepoDir) {
        return new SwarmProperties(
                true,
                new SwarmProperties.Workspace(sandboxDir.toString(), gitRepoDir),
                new SwarmProperties.Queue(64, 3),
                new SwarmProperties.Task(120, 1),
                new SwarmProperties.SwarmRun(10, 7),
                new SwarmProperties.Retention(true, 30, 50, 3_600_000L),
                new SwarmProperties.Process(30));
    }

    /** Build an engine. {@code grantedCsv} is the system ToolPermissionPolicy grant set. */
    public static Engine engine(Path sandboxDir, String grantedCsv) {
        return engine(sandboxDir, grantedCsv, new SameThreadExecutorService());
    }

    public static Engine engine(Path sandboxDir, String grantedCsv, ExecutorService executor) {
        return engine(sandboxDir, grantedCsv, executor, null);
    }

    /** Build an engine, optionally overriding the role executors (for deterministic blocking tests). */
    public static Engine engine(Path sandboxDir, String grantedCsv, ExecutorService executor,
                                List<RoleExecutor> executorsOverride) {
        return engine(sandboxDir, grantedCsv, executor, executorsOverride, null);
    }

    /**
     * Build an engine, optionally overriding the role executors and/or the {@link AgentTaskStore}
     * (e.g. a decorator that injects timing control for deterministic concurrency tests).
     */
    public static Engine engine(Path sandboxDir, String grantedCsv, ExecutorService executor,
                                List<RoleExecutor> executorsOverride, AgentTaskStore storeOverride) {
        SwarmProperties props = props(sandboxDir);
        ProcessRunner runner = new ProcessRunner();
        SandboxManager sandbox = new SandboxManager(props, runner);
        sandbox.init();

        RoleCatalog catalog = new RoleCatalog();
        AgentPermissionResolver resolver = new AgentPermissionResolver(catalog);
        SystemPanicState panic = new SystemPanicState();
        ToolPermissionPolicy policy = new ToolPermissionPolicy(grantedCsv);
        AgentAudit audit = new AgentAudit();
        SwarmMetrics metrics = new SwarmMetrics(new SimpleMeterRegistry());
        AgentActionGuard guard = new AgentActionGuard(panic, policy, audit);

        OutputSanitizer sanitizer = new OutputSanitizer();
        List<RoleExecutor> roleExecutors = executorsOverride != null ? executorsOverride : List.of(
                new CoderAgentExecutor(sandbox),
                new TesterAgentExecutor(runner, sanitizer, props),
                new DocsAgentExecutor(sandbox),
                new ResearchAgentExecutor(sandbox),
                new SecurityAgentExecutor(sandbox, sanitizer),
                new MediaAgentExecutor(),
                new FinanceAgentExecutor());
        RoleExecutorRegistry registry = new RoleExecutorRegistry(roleExecutors);

        AgentTaskStore store = storeOverride != null ? storeOverride : new InMemoryAgentTaskStore();
        AgentTaskService taskService = new AgentTaskService(store, executor, Clock.systemUTC(), catalog,
                resolver, sandbox, guard, registry, audit, metrics, props, new PendingPatchStore());
        SwarmCoordinator coordinator = new SwarmCoordinator(taskService, store, registry, props);

        return new Engine(taskService, store, panic, sandbox, catalog, resolver, guard, coordinator, props);
    }
}
