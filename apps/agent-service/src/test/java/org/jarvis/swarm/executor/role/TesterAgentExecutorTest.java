package org.jarvis.swarm.executor.role;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.config.SwarmProperties;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.permission.PermissionDeniedException;
import org.jarvis.swarm.process.OutputSanitizer;
import org.jarvis.swarm.process.ProcessRunner;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.sandbox.Sandbox;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.jarvis.swarm.task.AgentTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TesterAgentExecutorTest {

    @TempDir
    Path tmp;

    private static final SwarmProperties TEST_PROPS = new SwarmProperties(true,
            new SwarmProperties.Workspace("/tmp/tester-agent-executor-test", ""),
            new SwarmProperties.Queue(64, 3), new SwarmProperties.Task(120, 1),
            new SwarmProperties.SwarmRun(10, 7), new SwarmProperties.Retention(true, 30, 50, 3_600_000L),
            new SwarmProperties.Process(30));

    private final TesterAgentExecutor tester =
            new TesterAgentExecutor(new ProcessRunner(), new OutputSanitizer(), TEST_PROPS);

    private ExecutionContext ctx(String policyCsv, String goal, Set<ToolPermission> requested, Set<ToolPermission> granted) {
        var engine = SwarmTestFactory.engine(tmp, policyCsv);
        Sandbox sb = engine.sandbox().create("tester-" + Math.abs(goal.hashCode()));
        AgentTask task = SwarmTestFactory.task(AgentRole.TESTER, goal, requested, granted, false);
        return SwarmTestFactory.context(task, sb, engine.guard());
    }

    @Test
    void proposesCommandWhenShellNotRequested() {
        RoleResult result = tester.execute(ctx("READ_FILES", "run: echo hi", Set.of(), Set.of()));
        assertThat(result.success()).isTrue();
        assertThat(result.proposedActions()).anyMatch(a -> a.contains("echo hi"));
        assertThat(result.output()).isNull(); // not executed
    }

    @Test
    void runsAllowedCommandAndCapturesOutputWhenShellGranted() {
        RoleResult result = tester.execute(ctx("RUN_SHELL,READ_FILES", "run: echo hello",
                Set.of(ToolPermission.RUN_SHELL), Set.of(ToolPermission.RUN_SHELL)));
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("hello");
    }

    @Test
    void rejectsWhenShellRequestedButSystemPolicyDenies() {
        // user+role grant RUN_SHELL, but the system policy does not -> rejected
        assertThatThrownBy(() -> tester.execute(ctx("READ_FILES", "run: echo hi",
                Set.of(ToolPermission.RUN_SHELL), Set.of(ToolPermission.RUN_SHELL))))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void failingCommandIsReportedAsFailure() {
        RoleResult result = tester.execute(ctx("RUN_SHELL,READ_FILES", "run: false",
                Set.of(ToolPermission.RUN_SHELL), Set.of(ToolPermission.RUN_SHELL)));
        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("failed");
    }

    @Test
    void capturedOutputHasSecretsRedacted() {
        RoleResult result = tester.execute(ctx("RUN_SHELL,READ_FILES", "run: echo api_key=SUPERSECRET123",
                Set.of(ToolPermission.RUN_SHELL), Set.of(ToolPermission.RUN_SHELL)));
        assertThat(result.output()).doesNotContain("SUPERSECRET123");
        assertThat(result.output()).contains("[redacted-secret]");
    }
}
