package org.jarvis.desktop.features.planner

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.time.Instant
import java.util.UUID

class PlannerReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class TodoTask(
        val id: Long,
        val title: String,
        val description: String?,
        val priority: String,
        val status: String,
        val dueDate: Instant?,
        val tags: List<String>
    )

    data class Snapshot(
        val tasks: List<TodoTask>
    ) {
        val totalCount: Int = tasks.size
        val openCount: Int = tasks.count { it.status != "DONE" && it.status != "CANCELLED" }
        val doneCount: Int = tasks.count { it.status == "DONE" }
    }

    sealed interface BriefResult {
        data class Available(val text: String) : BriefResult
        data class Unavailable(val reason: String) : BriefResult
    }

    /** Today's focus from `GET /api/v1/planner/focus`; degrades gracefully. */
    fun loadFocus(): BriefResult = loadBrief("/planner/focus")

    /** Evening review from `GET /api/v1/planner/evening-review`; degrades gracefully. */
    fun loadEveningReview(): BriefResult = loadBrief("/planner/evening-review")

    private fun loadBrief(endpoint: String): BriefResult =
        runCatching { apiClient.get(endpoint) }.fold(
            onSuccess = { body -> BriefResult.Available(summarizeBrief(body)) },
            onFailure = { error -> BriefResult.Unavailable(error.message ?: "endpoint unavailable") }
        )

    private fun summarizeBrief(body: String): String {
        val root = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return body.trim().ifBlank { "(empty)" }
        val direct = listOf("focus", "summary", "message", "text", "review", "headline")
            .map { root.path(it) }
            .firstOrNull { !it.isMissingNode && !it.isNull && it.asText().isNotBlank() }
            ?.asText()
        if (direct != null) return direct
        val items = root.path("items").takeIf { it.isArray }
            ?: root.path("tasks").takeIf { it.isArray }
            ?: root.path("highlights").takeIf { it.isArray }
        if (items != null && items.size() > 0) {
            return items.joinToString("\n") { "• ${it.path("title").asText(it.asText("item"))}" }
        }
        return body.trim().ifBlank { "(empty)" }
    }

    fun loadSnapshot(): Snapshot {
        val payload = objectMapper.createObjectNode()
        val tasks = objectMapper.readTree(apiClient.post("/tools/todo/list", objectMapper.writeValueAsString(payload)))
            .map(::parseTask)
            .sortedWith(
                compareBy<TodoTask> { it.status == "DONE" || it.status == "CANCELLED" }
                    .thenBy { it.dueDate == null }
                    .thenBy { it.dueDate }
                    .thenBy { it.title.lowercase() }
            )
        return Snapshot(tasks)
    }

    fun createTodo(title: String, description: String?, priority: String): TodoTask {
        val payload = objectMapper.createObjectNode().apply {
            put("title", title.trim())
            description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
            put("priority", priority)
        }
        val response = apiClient.postWithHeaders(
            "/tools/todo/create",
            objectMapper.writeValueAsString(payload),
            idempotencyHeaders()
        )
        return parseTask(objectMapper.readTree(response))
    }

    fun completeTodo(id: Long): TodoTask {
        val payload = objectMapper.createObjectNode().apply {
            put("id", id)
        }
        val response = apiClient.postWithHeaders(
            "/tools/todo/complete",
            objectMapper.writeValueAsString(payload),
            idempotencyHeaders()
        )
        return parseTask(objectMapper.readTree(response))
    }

    private fun parseTask(node: JsonNode): TodoTask {
        return TodoTask(
            id = node.path("id").asLong(),
            title = node.path("title").asText("Untitled task"),
            description = node.stringOrNull("description"),
            priority = node.path("priority").asText("MEDIUM"),
            status = node.path("status").asText("TODO"),
            dueDate = node.stringOrNull("dueDate")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            tags = node.path("tags")
                .takeIf(JsonNode::isArray)
                ?.map(JsonNode::asText)
                ?: emptyList()
        )
    }

    private fun idempotencyHeaders(): Map<String, String> {
        return mapOf("X-Idempotency-Key" to UUID.randomUUID().toString())
    }

    private fun JsonNode.stringOrNull(field: String): String? {
        return path(field).asText(null)?.takeIf { it.isNotBlank() && it != "null" }
    }
}
