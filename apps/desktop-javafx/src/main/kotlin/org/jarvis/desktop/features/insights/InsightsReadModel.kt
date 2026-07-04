package org.jarvis.desktop.features.insights

import org.jarvis.desktop.api.ApiClient

/**
 * Read model for the Analytics Insights panel.
 *
 * Surfaces the analytics intelligence endpoints that the legacy analytics tab
 * does not cover:
 *  - GET /api/v1/analytics/insights
 *  - GET /api/v1/analytics/insights/day-score
 *  - GET /api/v1/analytics/insights/forecast
 *  - GET /api/v1/analytics/insights/report
 *
 * Each probe is independent and degrades to a structured [Result.Unavailable]
 * so one failing endpoint never blanks the whole panel.
 */
class InsightsReadModel(
    private val apiClient: ApiClient
) {
    sealed interface Result {
        data class Available(val body: String) : Result
        data class Unavailable(val reason: String) : Result
    }

    data class Snapshot(
        val insights: Result,
        val dayScore: Result,
        val forecast: Result,
        val report: Result
    )

    fun refresh(): Snapshot = Snapshot(
        insights = probe("/analytics/insights"),
        dayScore = probe("/analytics/insights/day-score"),
        forecast = probe("/analytics/insights/forecast"),
        report = probe("/analytics/insights/report")
    )

    private fun probe(path: String): Result =
        runCatching { apiClient.get(path) }.fold(
            onSuccess = { Result.Available(it) },
            onFailure = { Result.Unavailable(it.message ?: "endpoint unavailable") }
        )
}
