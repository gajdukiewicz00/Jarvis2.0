package org.jarvis.swarm.run;

import java.util.List;

/** One role's contribution to a swarm run. */
public record RoleOutcome(
        String role,
        String taskId,
        String status,
        String summary,
        String output,
        List<String> artifacts,
        List<String> risks) {

    public RoleOutcome {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        risks = risks == null ? List.of() : List.copyOf(risks);
    }
}
