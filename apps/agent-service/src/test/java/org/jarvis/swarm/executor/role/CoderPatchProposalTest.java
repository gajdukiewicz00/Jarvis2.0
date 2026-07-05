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

/**
 * Focused tests for CODER's unified-diff PATCH PROPOSAL output and GitDiffReport
 * artifact — separate from {@link CoderAgentExecutorTest} to isolate the new behavior.
 */
class CoderPatchProposalTest {

    @TempDir
    Path tmp;

    @Test
    void dryRunOutputContainsAUnifiedDiffPatchProposal() throws Exception {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        Sandbox sb = engine.sandbox().create("coder-patch-dry");
        AgentTask task = SwarmTestFactory.task(AgentRole.CODER, "build a login form",
                Set.of(ToolPermission.WRITE_FILES), Set.of(ToolPermission.WRITE_FILES), true);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new CoderAgentExecutor(engine.sandbox()).execute(ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("diff --git a/proposed/");
        assertThat(result.output()).contains("new file mode 100644");
        assertThat(result.output()).contains("+++ b/proposed/");
        assertThat(result.proposedActions()).anyMatch(a -> a.contains("patch proposal"));
        // dry-run must still write nothing
        try (var files = Files.list(sb.dir())) {
            assertThat(files.findAny()).isEmpty();
        }
    }

    @Test
    void applyingWritesADiffPatchArtifactAlongsidePlanAndStubs() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,WRITE_FILES");
        Sandbox sb = engine.sandbox().create("coder-patch-apply");
        AgentTask task = SwarmTestFactory.task(AgentRole.CODER, "add a caching layer",
                Set.of(ToolPermission.WRITE_FILES), Set.of(ToolPermission.WRITE_FILES), false);
        ExecutionContext ctx = SwarmTestFactory.context(task, sb, engine.guard());

        RoleResult result = new CoderAgentExecutor(engine.sandbox()).execute(ctx);

        assertThat(result.success()).isTrue();
        Path patch = sb.dir().resolve("DIFF.patch");
        assertThat(Files.exists(patch)).isTrue();
        assertThat(result.artifacts()).anyMatch(a -> a.endsWith("DIFF.patch"));

        String patchContent = readSafely(patch);
        assertThat(patchContent).contains("diff --git a/proposed/");
        assertThat(patchContent).contains("--- /dev/null");
    }

    private String readSafely(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
