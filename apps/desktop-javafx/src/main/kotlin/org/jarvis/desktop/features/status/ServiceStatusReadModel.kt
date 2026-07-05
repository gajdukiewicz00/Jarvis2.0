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
    )

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
