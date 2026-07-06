package org.jarvis.swarm.executor;

import java.util.List;

/**
 * Outcome of a role executor.
 *
 * @param success          whether the role completed its objective
 * @param summary          short human summary
 * @param output           captured textual output (already redacted/truncated; safe to surface)
 * @param artifacts        sandbox file paths produced
 * @param proposedActions  actions the role WOULD take (populated in dryRun / when denied)
 * @param risks            risks the role identified
 * @param nextActions      recommended follow-ups
 * @param awaitingApproval true when the role built an appliable proposal (currently only
 *                         CODER's patch) that is intentionally NOT applied yet — the task
 *                         stops at AWAITING_APPROVAL instead of completing; see
 *                         {@code AgentTaskService#approve}/{@code #reject}
 */
public record RoleResult(
        boolean success,
        String summary,
        String output,
        List<String> artifacts,
        List<String> proposedActions,
        List<String> risks,
        List<String> nextActions,
        boolean awaitingApproval) {

    public RoleResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        proposedActions = proposedActions == null ? List.of() : List.copyOf(proposedActions);
        risks = risks == null ? List.of() : List.copyOf(risks);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }

    public static RoleResult success(String summary, String output, List<String> artifacts,
                                     List<String> proposed, List<String> risks, List<String> next) {
        return new RoleResult(true, summary, output, artifacts, proposed, risks, next, false);
    }

    public static RoleResult failure(String summary, List<String> risks) {
        return new RoleResult(false, summary, null, List.of(), List.of(), risks, List.of(), false);
    }

    /** A proposal was built but deliberately not applied — awaiting explicit approval. */
    public static RoleResult awaitingApproval(String summary, String output, List<String> proposed,
                                              List<String> risks, List<String> next) {
        return new RoleResult(true, summary, output, List.of(), proposed, risks, next, true);
    }
}
