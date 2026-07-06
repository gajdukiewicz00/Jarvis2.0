package org.jarvis.swarm.executor.role;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.process.OutputSanitizer;
import org.jarvis.swarm.process.ProcessRunner;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.sandbox.Sandbox;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.jarvis.swarm.task.AgentTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end TESTER coverage for the real test-runner allowlist (mvn/gradle/npm/pytest
 * shapes) and pass/fail summarization, using a fake {@code mvn}-named script so the test
 * stays fast and deterministic (no real Maven build/network involved).
 */
class TesterRealTestCommandTest {

    @TempDir
    Path tmp;

    @Test
    void runsAnAllowlistedMavenShapedCommandAndSummarizesSurefireOutput() throws Exception {
        Path fakeMvn = tmp.resolve("mvn");
        Files.writeString(fakeMvn,
                "#!/bin/sh\necho 'Tests run: 3, Failures: 0, Errors: 0, Skipped: 0'\nexit 0\n");
        assertThat(fakeMvn.toFile().setExecutable(true)).isTrue();

        var engine = SwarmTestFactory.engine(tmp, "RUN_SHELL,READ_FILES");
        Sandbox sb = engine.sandbox().create("tester-real-mvn");
        AgentTask task = SwarmTestFactory.task(AgentRole.TESTER, "run: " + fakeMvn + " -q test",
                Set.of(ToolPermission.RUN_SHELL), Set.of(ToolPermission.RUN_SHELL), false);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new TesterAgentExecutor(new ProcessRunner(), new OutputSanitizer(),
                SwarmTestFactory.props(tmp)).execute(ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).contains("Tests run: 3, Failures: 0, Errors: 0, Skipped: 0");
        assertThat(result.output()).contains("Tests run: 3");
    }

    @Test
    void rejectsAMavenShapedCommandWithDisallowedFlags() {
        var engine = SwarmTestFactory.engine(tmp, "RUN_SHELL,READ_FILES");
        Sandbox sb = engine.sandbox().create("tester-real-mvn-reject");
        AgentTask task = SwarmTestFactory.task(AgentRole.TESTER, "run: mvn -Dexec.executable=/bin/sh test",
                Set.of(ToolPermission.RUN_SHELL), Set.of(ToolPermission.RUN_SHELL), false);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new TesterAgentExecutor(new ProcessRunner(), new OutputSanitizer(),
                SwarmTestFactory.props(tmp)).execute(ctx);

        assertThat(result.success()).isTrue(); // proposes only — never executes a disallowed shape
        assertThat(result.summary()).contains("not allowlisted");
        assertThat(result.output()).isNull();
    }

    @Test
    void rejectsMavenGoalOtherThanTest() {
        var engine = SwarmTestFactory.engine(tmp, "RUN_SHELL,READ_FILES");
        Sandbox sb = engine.sandbox().create("tester-real-mvn-clean");
        AgentTask task = SwarmTestFactory.task(AgentRole.TESTER, "run: mvn clean install",
                Set.of(ToolPermission.RUN_SHELL), Set.of(ToolPermission.RUN_SHELL), false);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new TesterAgentExecutor(new ProcessRunner(), new OutputSanitizer(),
                SwarmTestFactory.props(tmp)).execute(ctx);

        assertThat(result.summary()).contains("not allowlisted");
        assertThat(result.output()).isNull();
    }
}
