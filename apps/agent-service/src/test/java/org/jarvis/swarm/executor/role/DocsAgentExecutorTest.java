package org.jarvis.swarm.executor.role;

import org.jarvis.common.safety.ToolPermission;
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

class DocsAgentExecutorTest {

    @TempDir
    Path tmp;

    @Test
    void writesDocsIntoSandbox() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        Sandbox sb = engine.sandbox().create("docs-1");
        AgentTask task = SwarmTestFactory.task(AgentRole.DOCS, "document the API",
                Set.of(ToolPermission.WRITE_FILES), Set.of(ToolPermission.WRITE_FILES), false);
        RoleResult result = new DocsAgentExecutor(engine.sandbox())
                .execute(SwarmTestFactory.context(task, sb, engine.guard()));

        assertThat(result.success()).isTrue();
        assertThat(Files.exists(sb.dir().resolve("docs/README.md"))).isTrue();
    }

    @Test
    void dryRunProposesWithoutWriting() throws Exception {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        Sandbox sb = engine.sandbox().create("docs-2");
        AgentTask task = SwarmTestFactory.task(AgentRole.DOCS, "document the API", Set.of(), Set.of(), true);
        RoleResult result = new DocsAgentExecutor(engine.sandbox())
                .execute(SwarmTestFactory.context(task, sb, engine.guard()));

        assertThat(result.success()).isTrue();
        assertThat(result.proposedActions()).isNotEmpty();
        try (var files = Files.list(sb.dir())) {
            assertThat(files.findAny()).isEmpty();
        }
    }
}
