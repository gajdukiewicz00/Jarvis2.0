package org.jarvis.swarm.executor.role.coder;

import java.util.List;

/**
 * Everything needed to later APPLY a CODER patch proposal to its sandbox: the plan
 * document, the proposed new files (path + content), and the pre-rendered diff report.
 * Built once by {@code CoderAgentExecutor} and, when the task requires approval, staged in
 * {@link PendingPatchStore} until {@code AgentTaskService#approve} retrieves it and hands
 * it to {@link CoderPatchApplier}.
 */
public record PatchProposal(String planText, List<GitDiffReport.ProposedFile> proposedFiles,
                            GitDiffReport diffReport) {

    public PatchProposal {
        proposedFiles = proposedFiles == null ? List.of() : List.copyOf(proposedFiles);
    }
}
