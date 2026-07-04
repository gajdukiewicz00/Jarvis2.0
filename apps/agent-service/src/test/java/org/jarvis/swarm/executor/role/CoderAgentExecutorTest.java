package org.jarvis.swarm.executor.role;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleResult;
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

class CoderAgentExecutorTest {

    @TempDir
    Path tmp;

    @Test
    void dryRunReturnsProposedActionsAndWritesNothing() throws Exception {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        Sandbox sb = engine.sandbox().create("coder-dry");
        AgentTask task = SwarmTestFactory.task(AgentRole.CODER, "build a login form and validate input",
                Set.of(ToolPermission.WRITE_FILES), Set.of(ToolPermission.WRITE_FILES), true);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new CoderAgentExecutor(engine.sandbox()).execute(ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.proposedActions()).isNotEmpty();
        assertThat(result.artifacts()).isEmpty();
        // dry-run must not touch the sandbox
        try (var files = Files.list(sb.dir())) {
            assertThat(files.findAny()).isEmpty();
        }
    }

    @Test
    void realRunWritesPlanAndStubsIntoSandbox() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        Sandbox sb = engine.sandbox().create("coder-real");
        AgentTask task = SwarmTestFactory.task(AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), Set.of(ToolPermission.WRITE_FILES), false);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new CoderAgentExecutor(engine.sandbox()).execute(ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.artifacts()).isNotEmpty();
        assertThat(Files.exists(sb.dir().resolve("PLAN.md"))).isTrue();
    }
}
