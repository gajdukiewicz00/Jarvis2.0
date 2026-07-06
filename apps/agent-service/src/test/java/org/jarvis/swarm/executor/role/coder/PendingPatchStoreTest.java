package org.jarvis.swarm.executor.role.coder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests proving {@link PendingPatchStore} write-through persistence: a staged
 * proposal must survive the process restart that a brand-new instance simulates, so
 * {@code AgentTaskService#approve}/{@code #reject} keep working on an
 * AWAITING_APPROVAL task after a pod restart, exactly like {@code FileBackedAgentTaskStore}
 * does for the task record itself.
 */
class PendingPatchStoreTest {

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper();

    private PatchProposal proposal(String suffix) {
        GitDiffReport.ProposedFile file = new GitDiffReport.ProposedFile("proposed/Thing" + suffix + ".java",
                "// stub " + suffix);
        GitDiffReport diff = GitDiffReport.from(List.of(file));
        return new PatchProposal("# Plan " + suffix, List.of(file), diff);
    }

    @Test
    void stagedProposalIsNotVisibleToADifferentInstanceUntilReloaded() {
        PendingPatchStore first = new PendingPatchStore(mapper, tmp.toString());
        first.stage("t1", proposal("A"));

        PendingPatchStore second = new PendingPatchStore(mapper, tmp.toString());

        assertThat(second.hasPending("t1")).isTrue();
    }

    @Test
    void stagedProposalSurvivesReloadInANewInstanceWithFullContent() {
        PatchProposal original = proposal("A");
        PendingPatchStore first = new PendingPatchStore(mapper, tmp.toString());
        first.stage("t1", original);

        PendingPatchStore reloaded = new PendingPatchStore(mapper, tmp.toString());
        Optional<PatchProposal> found = reloaded.take("t1");

        assertThat(found).isPresent();
        assertThat(found.get().planText()).isEqualTo(original.planText());
        assertThat(found.get().proposedFiles()).isEqualTo(original.proposedFiles());
        assertThat(found.get().diffReport()).isEqualTo(original.diffReport());
    }

    @Test
    void takeRemovesFromMemoryAndDeletesTheBackingFileSoASubsequentReloadForgetsIt() {
        PendingPatchStore first = new PendingPatchStore(mapper, tmp.toString());
        first.stage("t1", proposal("A"));

        Optional<PatchProposal> taken = first.take("t1");

        assertThat(taken).isPresent();
        assertThat(first.hasPending("t1")).isFalse();

        PendingPatchStore reloaded = new PendingPatchStore(mapper, tmp.toString());
        assertThat(reloaded.hasPending("t1")).isFalse();
    }

    @Test
    void discardRemovesFromMemoryAndDeletesTheBackingFileSoASubsequentReloadForgetsIt() {
        PendingPatchStore first = new PendingPatchStore(mapper, tmp.toString());
        first.stage("t1", proposal("A"));

        first.discard("t1");

        assertThat(first.hasPending("t1")).isFalse();
        PendingPatchStore reloaded = new PendingPatchStore(mapper, tmp.toString());
        assertThat(reloaded.hasPending("t1")).isFalse();
    }

    @Test
    void takeOfAnUnknownTaskReturnsEmpty() {
        PendingPatchStore store = new PendingPatchStore(mapper, tmp.toString());

        assertThat(store.take("missing")).isEmpty();
    }

    @Test
    void multipleStagedProposalsAllSurviveReload() {
        PendingPatchStore first = new PendingPatchStore(mapper, tmp.toString());
        first.stage("t1", proposal("A"));
        first.stage("t2", proposal("B"));

        PendingPatchStore reloaded = new PendingPatchStore(mapper, tmp.toString());

        assertThat(reloaded.hasPending("t1")).isTrue();
        assertThat(reloaded.hasPending("t2")).isTrue();
    }
}
