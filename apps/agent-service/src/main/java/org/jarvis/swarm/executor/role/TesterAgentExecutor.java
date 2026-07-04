package org.jarvis.swarm.executor.role;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleExecutor;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.process.OutputSanitizer;
import org.jarvis.swarm.process.ProcessResult;
import org.jarvis.swarm.process.ProcessRunner;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.task.AgentTask;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * TESTER: proposes a test command, and RUNS it only when RUN_SHELL is effectively granted
 * (role ∩ user ∩ system policy). Even then, only an allowlisted binary is executed inside
 * the sandbox, with a timeout, and the output is secret-redacted and truncated. Without
 * the grant it proposes the command but never executes — and if the task explicitly
 * REQUESTED RUN_SHELL without an effective grant, the action is rejected.
 */
@Slf4j
@Component
public class TesterAgentExecutor implements RoleExecutor {

    /** Intentionally tiny, side-effect-free allowlist for the locked-down MVP container. */
    private static final Set<String> ALLOWLIST = Set.of("echo", "true", "false", "ls", "cat", "printf", "pwd");
    private static final int PROCESS_TIMEOUT_SECONDS = 30;

    private final ProcessRunner runner;
    private final OutputSanitizer sanitizer;

    public TesterAgentExecutor(ProcessRunner runner, OutputSanitizer sanitizer) {
        this.runner = runner;
        this.sanitizer = sanitizer;
    }

    @Override
    public AgentRole role() {
        return AgentRole.TESTER;
    }

    @Override
    public RoleResult execute(ExecutionContext ctx) {
        ctx.checkpoint();
        AgentTask task = ctx.task();
        List<String> command = parseCommand(task.goal());
        String pretty = String.join(" ", command);
        List<String> proposed = List.of("run: " + pretty);

        boolean requestedShell = task.permissionsRequested().contains(ToolPermission.RUN_SHELL);
        // If the task explicitly asked for shell, enforce it now — a missing grant rejects the task.
        if (requestedShell) {
            ctx.guard().ensurePermission(task, ToolPermission.RUN_SHELL);
        }

        boolean canRun = requestedShell
                && ctx.guard().isPermitted(task, ToolPermission.RUN_SHELL)
                && ALLOWLIST.contains(baseName(command.get(0)));

        if (ctx.dryRun() || !canRun) {
            String why = ctx.dryRun() ? "dry-run"
                    : !requestedShell ? "RUN_SHELL not requested"
                    : ALLOWLIST.contains(baseName(command.get(0))) ? "RUN_SHELL not granted"
                    : "binary not allowlisted";
            return RoleResult.success(
                    "TESTER proposed test command (" + why + "): " + pretty,
                    null, List.of(), proposed, List.of(), List.of("Grant RUN_SHELL to execute"));
        }

        try {
            Path cwd = ctx.sandbox() == null ? null : ctx.sandbox().dir();
            ProcessResult result = runner.run(command, cwd, PROCESS_TIMEOUT_SECONDS);
            String output = sanitizer.sanitize(result.output());
            if (result.isSuccess()) {
                return RoleResult.success("TESTER ran '" + pretty + "': passed (exit 0)",
                        output, List.of(), proposed, List.of(), List.of());
            }
            return RoleResult.failure("TESTER ran '" + pretty + "': failed (exit " + result.exitCode() + ")",
                    List.of("Test command exited non-zero", summarizeFailure(output)));
        } catch (Exception e) {
            return RoleResult.failure("TESTER could not run command: " + e.getMessage(),
                    List.of("execution error"));
        }
    }

    private List<String> parseCommand(String goal) {
        String g = goal == null ? "" : goal.trim();
        int idx = g.toLowerCase().indexOf("run:");
        if (idx >= 0) {
            String rest = g.substring(idx + 4).trim();
            if (!rest.isEmpty()) {
                return Arrays.stream(rest.split("\\s+")).toList();
            }
        }
        return List.of("mvn", "-q", "test");
    }

    private String baseName(String binary) {
        int slash = binary.lastIndexOf('/');
        return slash >= 0 ? binary.substring(slash + 1) : binary;
    }

    private String summarizeFailure(String output) {
        if (output == null || output.isBlank()) {
            return "no output captured";
        }
        String[] lines = output.split("\n");
        StringBuilder sb = new StringBuilder("first lines: ");
        for (int i = 0; i < Math.min(3, lines.length); i++) {
            sb.append(lines[i]).append(" | ");
        }
        return sb.toString();
    }
}
