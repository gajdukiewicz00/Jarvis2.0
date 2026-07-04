package org.jarvis.swarm.run;

import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
}
