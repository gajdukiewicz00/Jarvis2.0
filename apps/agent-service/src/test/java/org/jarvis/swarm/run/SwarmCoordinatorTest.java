package org.jarvis.swarm.run;

import org.jarvis.swarm.executor.role.CoderAgentExecutor;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SwarmCoordinatorTest {

    @TempDir
    Path tmp;

    @Test
    void runsCoderTesterDocsAndProducesCombinedReport() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        SwarmRun run = engine.coordinator().submit("u1", "build and document a feature",
                List.of(AgentRole.CODER, AgentRole.TESTER, AgentRole.DOCS), Set.of(), true);

        assertThat(run.taskIds()).hasSize(3);

        CombinedReport report = engine.coordinator().awaitAndReport("u1", run.swarmId());
        assertThat(report.complete()).isTrue();
        assertThat(report.rolesUsed()).contains("CODER", "TESTER", "DOCS");
        assertThat(report.perRole()).hasSize(3);
        assertThat(report.failedRoles()).isEmpty();
        assertThat(report.perRole()).allMatch(o -> o.status().equals("COMPLETED"));
    }

    @Test
    void partialFailureIsRepresentedHonestly() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        // MEDIA without MEDIA_ACCESS will be rejected -> a failed role alongside a completed one
        SwarmRun run = engine.coordinator().submit("u1", "edit a video and write code",
                List.of(AgentRole.CODER, AgentRole.MEDIA), Set.of(), true);

        CombinedReport report = engine.coordinator().awaitAndReport("u1", run.swarmId());
        assertThat(report.failedRoles()).contains("MEDIA");
        assertThat(report.perRole()).anyMatch(o -> o.role().equals("CODER") && o.status().equals("COMPLETED"));
        assertThat(report.nextActions()).anyMatch(a -> a.contains("failed roles"));
    }

    @Test
    void unknownSwarmIsNotFound() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.coordinator().report("u1", "swarm-nope"))
                .isInstanceOf(SwarmNotFoundException.class);
    }

    @Test
    void submitRejectsBlankGoal() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        assertThatThrownBy(() -> engine.coordinator().submit("u1", "  ", List.of(AgentRole.CODER), Set.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitRejectsEmptyRoleList() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        assertThatThrownBy(() -> engine.coordinator().submit("u1", "goal", List.of(), Set.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.coordinator().submit("u1", "goal", null, Set.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitRejectsTooManyRoles() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        List<AgentRole> tooMany = List.of(AgentRole.CODER, AgentRole.CODER, AgentRole.CODER, AgentRole.CODER,
                AgentRole.CODER, AgentRole.CODER, AgentRole.CODER, AgentRole.CODER); // 8 > maxRoles(7)
        assertThatThrownBy(() -> engine.coordinator().submit("u1", "goal", tooMany, Set.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many roles");
    }

    @Test
    void submitRejectsRoleWithoutRegisteredExecutor() {
        var sandboxManager = new org.jarvis.swarm.sandbox.SandboxManager(SwarmTestFactory.props(tmp));
        sandboxManager.init();
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES", new org.jarvis.swarm.support.SameThreadExecutorService(),
                List.of(new CoderAgentExecutor(sandboxManager)));
        assertThatThrownBy(() -> engine.coordinator().submit("u1", "goal", List.of(AgentRole.TESTER), Set.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no executor for role");
    }

    @Test
    void reportIsNotFoundForNonOwningUser() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        SwarmRun run = engine.coordinator().submit("u1", "build a thing", List.of(AgentRole.CODER), Set.of(), true);
        assertThatThrownBy(() -> engine.coordinator().report("intruder", run.swarmId()))
                .isInstanceOf(SwarmNotFoundException.class);
    }
}
