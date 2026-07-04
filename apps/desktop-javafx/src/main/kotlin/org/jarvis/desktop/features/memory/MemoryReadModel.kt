package org.jarvis.desktop.features.memory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient

/**
 * Read model for the Memory panel.
 *
 * Wires:
 *  - unified search  -> POST /api/v1/memory/search/unified
 *  - recent notes    -> GET  /api/v1/memory/notes
 *
 * Endpoints are relative to the desktop client's `/api/v1` base URL. Result
 * shapes vary by deployment, so parsing probes several common container keys
 * (`results`, `notes`, `items`, `data`) and falls back to an empty list.
 */
class MemoryReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class MemoryItem(
        val title: String,
        val snippet: String,
        val source: String,
        val score: Double?
    )

    fun search(query: String, limit: Int = 10): List<MemoryItem> {
        val payload = objectMapper.createObjectNode().apply {
            put("query", query.trim())
            put("limit", limit)
        }
        val response = apiClient.post("/memory/search/unified", objectMapper.writeValueAsString(payload))
        return parseItems(objectMapper.readTree(response))
    }

    fun recentNotes(limit: Int = 15): List<MemoryItem> {
        val response = apiClient.get("/memory/notes?limit=$limit")
        return parseItems(objectMapper.readTree(response))
    }

    private fun parseItems(root: JsonNode): List<MemoryItem> {
        val container = when {
            root.isArray -> root
            root.path("results").isArray -> root.path("results")
            root.path("notes").isArray -> root.path("notes")
            root.path("items").isArray -> root.path("items")
            root.path("data").isArray -> root.path("data")
            else -> return emptyList()
        }
        return container.map(::parseItem)
    }

    private fun parseItem(node: JsonNode): MemoryItem {
        val title = firstNonBlank(
            node.path("title").textOrNull(),
            node.path("name").textOrNull(),
            node.path("path").textOrNull(),
            node.path("id").textOrNull()
        ) ?: "Untitled note"
        val snippet = firstNonBlank(
            node.path("snippet").textOrNull(),
            node.path("content").textOrNull(),
            node.path("text").textOrNull(),
            node.path("body").textOrNull(),
            node.path("summary").textOrNull()
        ) ?: ""
        val source = firstNonBlank(
            node.path("source").textOrNull(),
            node.path("type").textOrNull(),
            node.path("kind").textOrNull()
        ) ?: "memory"
        val score = node.path("score").let { if (it.isNumber) it.asDouble() else null }
            ?: node.path("similarity").let { if (it.isNumber) it.asDouble() else null }
        return MemoryItem(title, snippet.take(500), source, score)
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
