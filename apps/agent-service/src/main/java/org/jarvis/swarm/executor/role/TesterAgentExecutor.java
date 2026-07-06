package org.jarvis.swarm.executor.role;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.config.SwarmProperties;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleExecutor;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.process.MavenFailureParser;
import org.jarvis.swarm.process.OutputSanitizer;
import org.jarvis.swarm.process.ProcessResult;
import org.jarvis.swarm.process.ProcessRunner;
import org.jarvis.swarm.process.TestCommandAllowlist;
import org.jarvis.swarm.process.TestFailure;
import org.jarvis.swarm.process.TestOutputSummarizer;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.task.AgentTask;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TESTER: proposes a test command, and RUNS it only when RUN_SHELL is effectively granted
 * (role ∩ user ∩ system policy). Even then, only a {@link TestCommandAllowlist}-approved
 * command is executed inside the sandbox, with a configurable, hard kill-on-overrun
 * timeout ({@code swarm.process.test-command-timeout-seconds}, see {@link ProcessRunner}),
 * and the output is secret-redacted and truncated. Without the grant it proposes the
 * command but never executes — and if the task explicitly REQUESTED RUN_SHELL without an
 * effective grant, the action is rejected. Real test-runner invocations
 * (mvn/gradle/npm/pytest) are validated argument-by-argument; their output is scanned for
 * a recognizable pass/fail summary line (Surefire, Gradle, Jest, pytest formats) and, for
 * Maven/Surefire, individual failures are parsed into structured {@link TestFailure}
 * entries (class#method + message) instead of dumping the raw log into risks.
 */
@Slf4j
@Component
public class TesterAgentExecutor implements RoleExecutor {

    private final ProcessRunner runner;
    private final OutputSanitizer sanitizer;
    private final int timeoutSeconds;

    public TesterAgentExecutor(ProcessRunner runner, OutputSanitizer sanitizer, SwarmProperties props) {
        this.runner = runner;
        this.sanitizer = sanitizer;
        this.timeoutSeconds = props.process().testCommandTimeoutSeconds();
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

        boolean allowlisted = TestCommandAllowlist.isAllowed(command);
        boolean canRun = requestedShell
                && ctx.guard().isPermitted(task, ToolPermission.RUN_SHELL)
                && allowlisted;

        if (ctx.dryRun() || !canRun) {
            String why = ctx.dryRun() ? "dry-run"
                    : !requestedShell ? "RUN_SHELL not requested"
                    : allowlisted ? "RUN_SHELL not granted"
                    : "command not allowlisted";
            return RoleResult.success(
                    "TESTER proposed test command (" + why + "): " + pretty,
                    null, List.of(), proposed, List.of(), List.of("Grant RUN_SHELL to execute"));
        }

        try {
            Path cwd = ctx.sandbox() == null ? null : ctx.sandbox().dir();
            ProcessResult result = runner.run(command, cwd, timeoutSeconds);
            String output = sanitizer.sanitize(result.output());
            String testSummary = TestOutputSummarizer.summarize(output);
            if (result.isSuccess()) {
                String summary = "TESTER ran '" + pretty + "': passed (exit 0)"
                        + (testSummary.isEmpty() ? "" : " — " + testSummary);
                return RoleResult.success(summary, output, List.of(), proposed, List.of(), List.of());
            }
            List<String> risks = new ArrayList<>();
            risks.add(result.timedOut()
                    ? "Test command timed out after " + timeoutSeconds + "s and was killed"
                    : "Test command exited non-zero");
            List<TestFailure> failures = MavenFailureParser.parse(output);
            if (!failures.isEmpty()) {
                failures.forEach(f -> risks.add("FAILED " + f.describe()));
            } else {
                risks.add(summarizeFailure(output));
            }
            if (!testSummary.isEmpty()) {
                risks.add(testSummary);
            }
            return RoleResult.failure("TESTER ran '" + pretty + "': failed (exit " + result.exitCode() + ")", risks);
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
