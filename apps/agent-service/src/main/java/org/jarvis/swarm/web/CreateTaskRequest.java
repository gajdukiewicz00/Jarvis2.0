package org.jarvis.swarm.web;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Create-task request.
 *
 * @param role        role name (CODER/TESTER/RESEARCH/DOCS/SECURITY/MEDIA/FINANCE)
 * @param goal        the task goal
 * @param permissions permission names the user grants to this task (e.g. ["RUN_SHELL"])
 * @param dryRun      when true, propose actions without side effects
 */
public record CreateTaskRequest(
        @NotBlank String role,
        @NotBlank String goal,
        List<String> permissions,
        boolean dryRun) {
}
