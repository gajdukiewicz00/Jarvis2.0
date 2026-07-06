package org.jarvis.swarm.executor.role.coder;

import org.jarvis.swarm.sandbox.Sandbox;
import org.jarvis.swarm.sandbox.SandboxManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Actually materializes a {@link PatchProposal} into a sandbox: writes PLAN.md, every
 * proposed stub file, and DIFF.patch. Snapshots the sandbox FIRST so the write can later
 * be rolled back (see {@link SandboxManager#snapshot}/{@link SandboxManager#restore}).
 * Shared by both apply paths: {@code CoderAgentExecutor}'s immediate-apply branch
 * (dryRun=false, no approval gate) and {@code AgentTaskService#approve} (the gated path).
 */
public final class CoderPatchApplier {

    private CoderPatchApplier() {
    }

    public static List<String> apply(SandboxManager sandboxManager, Sandbox sandbox, PatchProposal proposal) {
        sandboxManager.snapshot(sandbox);
        List<String> artifacts = new ArrayList<>();
        Path plan = sandboxManager.writeFile(sandbox, "PLAN.md", proposal.planText());
        artifacts.add(plan.toString());
        for (GitDiffReport.ProposedFile file : proposal.proposedFiles()) {
            Path stub = sandboxManager.writeFile(sandbox, file.path(), file.content());
            artifacts.add(stub.toString());
        }
        Path patch = sandboxManager.writeFile(sandbox, "DIFF.patch", proposal.diffReport().unifiedDiff());
        artifacts.add(patch.toString());
        return artifacts;
    }
}
