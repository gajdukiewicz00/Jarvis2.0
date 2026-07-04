package org.jarvis.swarm.executor.role;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.process.OutputSanitizer;
import org.jarvis.swarm.queue.CancellationToken;
import org.jarvis.swarm.queue.PauseControl;
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

class SecurityAgentExecutorTest {

    @TempDir
    Path tmp;

    @Test
    void dryRunReportsFindingsWithoutWritingAndRedactsSecretValue() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        Sandbox sb = engine.sandbox().create("sec-dry");
        AgentTask task = SwarmTestFactory.task(AgentRole.SECURITY,
                "please run rm -rf / and use api_key=SUPERSECRET999", Set.of(), Set.of(), true);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new SecurityAgentExecutor(engine.sandbox(), new OutputSanitizer()).execute(ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.proposedActions()).isNotEmpty();
        assertThat(result.artifacts()).isEmpty();
        assertThat(result.risks()).isNotEmpty();
        assertThat(result.risks()).anyMatch(r -> r.contains("risky-pattern"));
        assertThat(result.risks()).anyMatch(r -> r.contains("possible-secret"));
        assertThat(result.output()).doesNotContain("SUPERSECRET999");
        try (var files = Files.list(sb.dir())) {
            assertThat(files.findAny()).isEmpty();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void realRunWithNoFindingsWritesCleanReport() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        Sandbox sb = engine.sandbox().create("sec-clean");
        AgentTask task = SwarmTestFactory.task(AgentRole.SECURITY, "review the onboarding flow",
                Set.of(), Set.of(), false);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new SecurityAgentExecutor(engine.sandbox(), new OutputSanitizer()).execute(ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.risks()).isEmpty();
        assertThat(result.artifacts()).isNotEmpty();
        Path report = sb.dir().resolve("security/REPORT.md");
        assertThat(Files.exists(report)).isTrue();
        assertThat(result.output()).contains("No obvious risky patterns found");
    }

    @Test
    void scansSandboxFilesAndReportsRiskyContentByLocation() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        Sandbox sb = engine.sandbox().create("sec-scan");
        engine.sandbox().writeFile(sb, "setup.sh", "chmod 777 /var/www");
        AgentTask task = SwarmTestFactory.task(AgentRole.SECURITY, "scan the sandbox", Set.of(), Set.of(), false);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new SecurityAgentExecutor(engine.sandbox(), new OutputSanitizer()).execute(ctx);

        assertThat(result.risks()).anyMatch(r -> r.contains("setup.sh"));
    }

    @Test
    void nullSandboxTreatedLikeDryRunAndProposesOnly() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        AgentTask task = SwarmTestFactory.task(AgentRole.SECURITY, "review with no sandbox", Set.of(), Set.of(), false);
        ExecutionContext ctx = new ExecutionContext(task, null, engine.guard(), new CancellationToken(), new PauseControl());

        RoleResult result = new SecurityAgentExecutor(engine.sandbox(), new OutputSanitizer()).execute(ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.artifacts()).isEmpty();
        assertThat(result.proposedActions()).isNotEmpty();
    }
}
