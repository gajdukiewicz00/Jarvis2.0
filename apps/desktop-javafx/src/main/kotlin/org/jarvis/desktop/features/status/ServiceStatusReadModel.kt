package org.jarvis.desktop.features.status

import org.jarvis.agent.status.StatusAggregator
import org.jarvis.desktop.config.AppConfig
import java.time.Instant

/**
 * Read model backing the top-bar status indicator and the Service Status
 * panel. Delegates the actual polling to [StatusAggregator] (already used by
 * the native desktop agent daemon) so the health-check targets and parsing
 * stay in one place; this class only adapts it to the currently resolved
 * API gateway base URL and rebuilds the aggregator if that URL changes
 * (e.g. after a manual endpoint override is saved in Settings).
 */
class ServiceStatusReadModel(
    private val baseUrlProvider: () -> String = { AppConfig.current().apiGatewayBaseUrl }
) {
    data class Snapshot(
        val refreshedAt: Instant,
        val baseUrl: String,
        val services: List<StatusAggregator.ServiceStatus>
    ) {
        /**
         * Count of services that are actually reachable — UP or PROTECTED
         * (alive, just gated behind auth). This is the number the top bar and
         * summary pill should present as "healthy", not a strict UP-only count.
         *
         * Delegates to [StatusLevel.isHealthy] (via [toStatusLevel]) rather than
         * re-deciding "healthy" locally, so this agrees with every other screen
         * that classifies the same [StatusAggregator.ProbeStatus] values.
         */
        val healthyCount: Int
            get() = services.count { it.status.toStatusLevel().isHealthy }

        /** Services that are genuinely unhealthy (DEGRADED or DOWN) — excludes PROTECTED. */
        val downServices: List<StatusAggregator.ServiceStatus>
            get() = services.filterNot { it.status.toStatusLevel().isHealthy }
    }

    @Volatile private var aggregator: StatusAggregator? = null
    @Volatile private var aggregatorBaseUrl: String? = null

    fun refresh(): Snapshot {
        val baseUrl = baseUrlProvider()
        val statuses = resolveAggregator(baseUrl).refresh().values.sortedBy { it.name }
        return Snapshot(refreshedAt = Instant.now(), baseUrl = baseUrl, services = statuses)
    }

    private fun resolveAggregator(baseUrl: String): StatusAggregator {
        val current = aggregator
        if (current != null && aggregatorBaseUrl == baseUrl) {
            return current
        }
        return StatusAggregator(backendBaseUrl = baseUrl).also {
            aggregator = it
            aggregatorBaseUrl = baseUrl
        }
    }
}

/**
 * Canonical mapping from [StatusAggregator.ProbeStatus] onto the shared
 * [StatusLevel] vocabulary. This is what [ServiceStatusReadModel.Snapshot] and
 * [ServiceStatusView] both go through, so "is this service healthy?" and
 * "what color does it render in?" can never disagree between the two.
 */
fun StatusAggregator.ProbeStatus.toStatusLevel(): StatusLevel = when (this) {
    StatusAggregator.ProbeStatus.UP -> StatusLevel.UP
    // Reachable, just gated behind auth — never DEGRADED/DOWN. See StatusLevel.PROTECTED.
    StatusAggregator.ProbeStatus.PROTECTED -> StatusLevel.PROTECTED
    StatusAggregator.ProbeStatus.DEGRADED -> StatusLevel.DEGRADED
    StatusAggregator.ProbeStatus.DOWN -> StatusLevel.DOWN
}
