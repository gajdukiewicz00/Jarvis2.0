package org.jarvis.swarm.web;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Create-task request.
 *
 * @param role           role name (CODER/TESTER/RESEARCH/DOCS/SECURITY/MEDIA/FINANCE)
 * @param goal           the task goal
 * @param permissions    permission names the user grants to this task (e.g. ["RUN_SHELL"])
 * @param dryRun         when true, propose actions without side effects
 * @param idempotencyKey   optional client-supplied key; a repeated submit with the same key
 *                         (scoped to the caller) returns the existing task instead of starting
 *                         a second run
 * @param approvalRequired when true (and dryRun is false), a CODER patch proposal stops at
 *                         AWAITING_APPROVAL instead of applying immediately — see
 *                         {@code AgentTaskController#approve}/{@code #reject}
 */
public record CreateTaskRequest(
        @NotBlank String role,
        @NotBlank String goal,
        List<String> permissions,
        boolean dryRun,
        String idempotencyKey,
        boolean approvalRequired) {

    /** Convenience constructor for callers that don't supply an idempotency key or approval gate. */
    public CreateTaskRequest(String role, String goal, List<String> permissions, boolean dryRun) {
        this(role, goal, permissions, dryRun, null, false);
    }

    /** Convenience constructor for callers that supply an idempotency key but no approval gate. */
    public CreateTaskRequest(String role, String goal, List<String> permissions, boolean dryRun,
                              String idempotencyKey) {
        this(role, goal, permissions, dryRun, idempotencyKey, false);
    }
}
