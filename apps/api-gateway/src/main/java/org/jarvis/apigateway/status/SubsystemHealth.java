package org.jarvis.apigateway.status;

/**
 * Coarse health rollup used by the {@code /api/v1/status/report} aggregator.
 *
 * <ul>
 *   <li>{@code OK} — reachable and serving.</li>
 *   <li>{@code DEGRADED} — intentionally disabled by a feature flag, or partially
 *       available (e.g. a subsystem is up but no desktop client is connected).</li>
 *   <li>{@code BROKEN} — expected to be available but not reachable.</li>
 * </ul>
 */
public enum SubsystemHealth {
    OK,
    DEGRADED,
    BROKEN
}
