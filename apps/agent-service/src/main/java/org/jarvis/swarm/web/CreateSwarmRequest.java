package org.jarvis.swarm.web;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Create-swarm request: one goal fanned out across several roles.
 *
 * @param goal        the shared goal
 * @param roles       role names to run
 * @param permissions permission names granted to every child task
 * @param dryRun          when true, all roles propose without side effects
 * @param awaitCompletion when true, block (up to the configured timeout) and return the combined report
 */
public record CreateSwarmRequest(
        @NotEmpty String goal,
        @NotEmpty List<String> roles,
        List<String> permissions,
        boolean dryRun,
        boolean awaitCompletion) {
}
