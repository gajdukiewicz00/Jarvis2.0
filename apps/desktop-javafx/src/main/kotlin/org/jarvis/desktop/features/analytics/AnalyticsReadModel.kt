package org.jarvis.desktop.features.analytics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient

/**
 * Read model for the Analytics dashboard's chart data.
 *
 * Feeds [AnalyticsView]'s real JavaFX charts from the analytics-service
 * expense endpoints:
 *  - GET /api/v1/analytics/expenses/by-month    -> `List<ExpenseSummaryDTO>` (bar chart)
 *  - GET /api/v1/analytics/expenses/by-category -> `List<ExpenseSummaryDTO>` (pie chart)
 *  - GET /api/v1/analytics/expenses/trend       -> `ChartDataDTO`            (line chart)
 *
 * Each probe is independent and degrades to a structured [Result.Unavailable]
 * so one failing endpoint never blanks the whole dashboard.
 */
class AnalyticsReadModel(
    private val apiClient: ApiClient
) {
    sealed interface Result<out T> {
        data class Available<T>(val data: T) : Result<T>
        data class Unavailable(val reason: String) : Result<Nothing>
    }

    /** One point of an expense summary, grouped either by month or by category. */
    data class ExpensePoint(
        val label: String,
        val amount: Double,
        val currency: String,
        val count: Int
    )

    /** A generic labeled series, as returned by the expense-trend endpoint. */
    data class Series(
        val title: String,
        val xAxisLabel: String,
        val yAxisLabel: String,
        val points: List<Pair<String, Double>>
    )

    data class Snapshot(
        val byMonth: Result<List<ExpensePoint>>,
        val byCategory: Result<List<ExpensePoint>>,
        val trend: Result<Series>
    )

    private val objectMapper = jacksonObjectMapper()

    fun refresh(): Snapshot = Snapshot(
        byMonth = probeExpenses("/analytics/expenses/by-month", groupField = "period"),
        byCategory = probeExpenses("/analytics/expenses/by-category", groupField = "category"),
        trend = probeTrend("/analytics/expenses/trend?period=month")
    )

    private fun probeExpenses(path: String, groupField: String): Result<List<ExpensePoint>> =
        runCatching {
            val root = objectMapper.readTree(apiClient.get(path))
            if (!root.isArray) emptyList() else root.map { it.toExpensePoint(groupField) }
        }.fold(
            onSuccess = { Result.Available(it) },
            onFailure = { Result.Unavailable(it.message ?: "endpoint unavailable") }
        )

    private fun probeTrend(path: String): Result<Series> =
        runCatching {
            val root = objectMapper.readTree(apiClient.get(path))
            val labels = root.path("labels").takeIf(JsonNode::isArray)?.map { it.asText() } ?: emptyList()
            val values = root.path("values").takeIf(JsonNode::isArray)?.map { it.asDouble(0.0) } ?: emptyList()
            Series(
                title = root.path("title").textOrNull() ?: "Expense trend",
                xAxisLabel = root.path("xAxisLabel").textOrNull() ?: "",
                yAxisLabel = root.path("yAxisLabel").textOrNull() ?: "",
                points = labels.zip(values)
            )
        }.fold(
            onSuccess = { Result.Available(it) },
            onFailure = { Result.Unavailable(it.message ?: "endpoint unavailable") }
        )

    private fun JsonNode.toExpensePoint(groupField: String): ExpensePoint = ExpensePoint(
        label = path(groupField).textOrNull() ?: "—",
        amount = path("totalAmount").asDouble(0.0),
        currency = path("currency").textOrNull() ?: "",
        count = path("count").asInt(0)
    )

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
