package org.jarvis.media.job;

import java.util.List;
import java.util.Map;

/** The successful result of a job step: produced artifacts plus structured details. */
public record JobOutcome(List<JobArtifact> artifacts, Map<String, Object> details) {

    public JobOutcome {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static JobOutcome of(List<JobArtifact> artifacts, Map<String, Object> details) {
        return new JobOutcome(artifacts, details);
    }
}
