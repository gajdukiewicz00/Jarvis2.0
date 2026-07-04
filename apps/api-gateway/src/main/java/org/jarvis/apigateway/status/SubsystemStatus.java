package org.jarvis.apigateway.status;

/**
 * Status of a single Jarvis subsystem as reported by {@code /api/v1/status/report}.
 *
 * @param subsystem human-readable subsystem name (Voice, Vision, LLM, Memory,
 *                  Desktop, Commands, Infra)
 * @param status    coarse {@link SubsystemHealth} rollup
 * @param detail    short, operator-readable explanation (probe target, disabled
 *                  reason, connected client count, …)
 */
public record SubsystemStatus(String subsystem, SubsystemHealth status, String detail) {
}
