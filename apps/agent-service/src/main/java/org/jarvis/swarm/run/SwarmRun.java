package org.jarvis.swarm.run;

import java.util.List;

/** Acknowledgement returned when a swarm run is created. */
public record SwarmRun(String swarmId, String goal, List<String> roles, List<String> taskIds, boolean dryRun) {

    public SwarmRun {
        roles = roles == null ? List.of() : List.copyOf(roles);
        taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
    }
}
