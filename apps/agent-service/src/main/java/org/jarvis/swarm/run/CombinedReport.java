package org.jarvis.swarm.run;

import java.util.List;

/**
 * Honest combined report for a swarm run: what was attempted, what each role produced,
 * which roles failed, accumulated risks, and recommended next actions. Partial failure is
 * represented faithfully — a failed role appears in {@code failedRoles} and its outcome
 * carries its real status.
 */
public record CombinedReport(
        String swarmId,
        String goal,
        boolean complete,
        List<String> rolesUsed,
        List<RoleOutcome> perRole,
        List<String> failedRoles,
        List<String> risks,
        List<String> nextActions) {

    public CombinedReport {
        rolesUsed = rolesUsed == null ? List.of() : List.copyOf(rolesUsed);
        perRole = perRole == null ? List.of() : List.copyOf(perRole);
        failedRoles = failedRoles == null ? List.of() : List.copyOf(failedRoles);
        risks = risks == null ? List.of() : List.copyOf(risks);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
