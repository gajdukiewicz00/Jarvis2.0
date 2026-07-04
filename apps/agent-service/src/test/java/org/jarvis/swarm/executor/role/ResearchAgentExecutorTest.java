package org.jarvis.swarm.executor.role;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.permission.PermissionDeniedException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchAgentExecutorTest {

    @TempDir
    Path tmp;

    @Test
    void realRunWithoutNetworkWritesOfflineNotes() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        Sandbox sb = engine.sandbox().create("research-offline");
        AgentTask task = SwarmTestFactory.task(AgentRole.RESEARCH, "research quantum computing",
                Set.of(), Set.of(), false);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new ResearchAgentExecutor(engine.sandbox()).execute(ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.artifacts()).isNotEmpty();
        assertThat(Files.exists(sb.dir().resolve("research/NOTES.md"))).isTrue();
        assertThat(result.output()).contains("Offline only.");
        assertThat(result.nextActions()).anyMatch(n -> n.contains("Grant NETWORK_ACCESS"));
    }

    @Test
    void dryRunProposesWithoutWriting() throws Exception {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        Sandbox sb = engine.sandbox().create("research-dry");
        AgentTask task = SwarmTestFactory.task(AgentRole.RESEARCH, "research caching strategies",
                Set.of(), Set.of(), true);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new ResearchAgentExecutor(engine.sandbox()).execute(ctx);

        assertThat(result.summary()).contains("dry-run");
        assertThat(result.proposedActions()).isNotEmpty();
        try (var files = Files.list(sb.dir())) {
            assertThat(files.findAny()).isEmpty();
        }
    }

    @Test
    void networkGrantedProducesOnlineModeNotes() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,NETWORK_ACCESS");
        Sandbox sb = engine.sandbox().create("research-online");
        AgentTask task = SwarmTestFactory.task(AgentRole.RESEARCH, "research news",
                Set.of(ToolPermission.NETWORK_ACCESS), Set.of(ToolPermission.NETWORK_ACCESS), false);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new ResearchAgentExecutor(engine.sandbox()).execute(ctx);

        assertThat(result.output()).contains("Online research permitted");
        assertThat(result.nextActions()).anyMatch(n -> n.contains("not wired in this MVP"));
    }

    @Test
    void networkRequestedButDeniedRejectsTask() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES"); // system policy lacks NETWORK_ACCESS
        Sandbox sb = engine.sandbox().create("research-denied");
        AgentTask task = SwarmTestFactory.task(AgentRole.RESEARCH, "research news",
                Set.of(ToolPermission.NETWORK_ACCESS), Set.of(ToolPermission.NETWORK_ACCESS), false);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        assertThatThrownBy(() -> new ResearchAgentExecutor(engine.sandbox()).execute(ctx))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void nullSandboxBehavesLikeDryRun() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        AgentTask task = SwarmTestFactory.task(AgentRole.RESEARCH, "research without sandbox",
                Set.of(), Set.of(), false);
        ExecutionContext ctx = new ExecutionContext(task, null, engine.guard(), new CancellationToken(), new PauseControl());

        RoleResult result = new ResearchAgentExecutor(engine.sandbox()).execute(ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.artifacts()).isEmpty();
    }
}
