package org.jarvis.desktop.features.proactive

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient

/**
 * Read model for the Proactive panel.
 *
 * Surfaces recent proactive observations / state. The exact route differs
 * across deployments, so we probe a small set of candidates and degrade
 * gracefully when none respond (returning [Result.Unavailable] rather than
 * throwing).
 */
class ProactiveReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class Observation(
        val title: String,
        val detail: String,
        val timestamp: String
    )

    sealed interface Result {
        data class Available(val observations: List<Observation>, val raw: String) : Result
        data class Unavailable(val reason: String) : Result
    }

    fun recentObservations(): Result {
        val candidates = listOf(
            "/proactive/observations",
            "/proactive/state",
            "/proactive/recent"
        )
        var lastError: String? = null
        for (path in candidates) {
            val outcome = runCatching { apiClient.get(path) }
            outcome.onSuccess { body ->
                return Result.Available(parse(body), body)
            }
            outcome.onFailure { lastError = it.message }
        }
        return Result.Unavailable(
            "Proactive observation feed is not reachable. " +
                "The proactive loop runs host-side and may not be exposed through the gateway. " +
                "(${lastError ?: "no endpoint responded"})"
        )
    }

    private fun parse(body: String): List<Observation> {
        val root = runCatching { objectMapper.readTree(body) }.getOrNull() ?: return emptyList()
        val container = when {
            root.isArray -> root
            root.path("observations").isArray -> root.path("observations")
            root.path("events").isArray -> root.path("events")
            root.path("items").isArray -> root.path("items")
            root.path("data").isArray -> root.path("data")
            else -> return emptyList()
        }
        return container.map(::parseOne)
    }

    private fun parseOne(node: JsonNode): Observation {
        val title = firstNonBlank(
            node.path("title").textOrNull(),
            node.path("type").textOrNull(),
            node.path("event").textOrNull(),
            node.path("kind").textOrNull()
        ) ?: "Observation"
        val detail = firstNonBlank(
            node.path("detail").textOrNull(),
            node.path("message").textOrNull(),
            node.path("description").textOrNull(),
            node.path("text").textOrNull(),
            node.path("summary").textOrNull()
        ) ?: ""
        val timestamp = firstNonBlank(
            node.path("timestamp").textOrNull(),
            node.path("createdAt").textOrNull(),
            node.path("time").textOrNull(),
            node.path("at").textOrNull()
        ) ?: ""
        return Observation(title, detail.take(500), timestamp)
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
