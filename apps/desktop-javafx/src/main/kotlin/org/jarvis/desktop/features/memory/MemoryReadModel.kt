package org.jarvis.desktop.features.memory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Read model for the Memory panel.
 *
 * Wires:
 *  - unified search  -> POST   /api/v1/memory/search/unified
 *  - recent notes    -> GET    /api/v1/memory/notes
 *  - edit note       -> PUT    /api/v1/memory/notes/{memoryId}
 *  - forget note      -> DELETE /api/v1/memory/notes/{memoryId}?actor=&reason=
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
        val memoryId: String?,
        val title: String,
        val snippet: String,
        val source: String,
        val score: Double?
    ) {
        /**
         * Edit/forget only apply to real memory notes. Unified search also
         * returns conversation chunks (`source == "conversation"`) whose
         * `id` is a chunk id, not a `/memory/notes/{memoryId}` key.
         */
        val isManageable: Boolean
            get() = !memoryId.isNullOrBlank() && source != "conversation"
    }

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

    /** Full note detail for editing — list snippets are truncated to 500 chars. */
    data class NoteDetail(val memoryId: String, val title: String, val body: String)

    fun getNote(memoryId: String): NoteDetail {
        val response = apiClient.get("/memory/notes/${encode(memoryId)}")
        val node = objectMapper.readTree(response)
        return NoteDetail(
            memoryId = firstNonBlank(node.path("memoryId").textOrNull(), node.path("id").textOrNull())
                ?: memoryId,
            title = node.path("title").textOrNull() ?: "",
            body = firstNonBlank(node.path("body").textOrNull(), node.path("summary").textOrNull()) ?: ""
        )
    }

    /** Partial update — only [title]/[body] are sent, so untouched fields survive. */
    fun updateNote(memoryId: String, title: String, body: String) {
        val payload = objectMapper.createObjectNode().apply {
            put("title", title.trim())
            put("body", body)
        }
        apiClient.put("/memory/notes/${encode(memoryId)}", objectMapper.writeValueAsString(payload))
    }

    /** "Jarvis, forget this" — soft-deletes the note. HIGH-risk, confirm before calling. */
    fun forgetNote(memoryId: String, actor: String?, reason: String?) {
        val query = buildString {
            append("/memory/notes/").append(encode(memoryId))
            val params = mutableListOf<String>()
            actor?.takeIf { it.isNotBlank() }?.let { params += "actor=${encode(it)}" }
            reason?.takeIf { it.isNotBlank() }?.let { params += "reason=${encode(it)}" }
            if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
        }
        apiClient.delete(query)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

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
        val memoryId = firstNonBlank(
            node.path("memoryId").textOrNull(),
            node.path("id").textOrNull()
        )
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
        return MemoryItem(memoryId, title, snippet.take(500), source, score)
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
