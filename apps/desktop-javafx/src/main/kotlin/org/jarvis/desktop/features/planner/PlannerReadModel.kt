package org.jarvis.desktop.features.planner

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.time.Instant
import java.time.LocalDate
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

    /** This week's task distribution from `GET /api/v1/planner/weekly`; degrades gracefully. */
    fun loadWeeklyPlan(): BriefResult =
        runCatching { apiClient.get("/planner/weekly") }.fold(
            onSuccess = { body -> BriefResult.Available(summarizeWeeklyPlan(body)) },
            onFailure = { error -> BriefResult.Unavailable(error.message ?: "endpoint unavailable") }
        )

    /** Tomorrow's plan from `GET /api/v1/planner/daily?date=<ISO>`; degrades gracefully. */
    fun loadTomorrowPlan(): BriefResult {
        val tomorrow = LocalDate.now().plusDays(1)
        return runCatching { apiClient.get("/planner/daily?date=$tomorrow") }.fold(
            onSuccess = { body -> BriefResult.Available(summarizeDailyPlan(body)) },
            onFailure = { error -> BriefResult.Unavailable(error.message ?: "endpoint unavailable") }
        )
    }

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

    /** Formats the `days` map from `WeeklyPlanGenerator` (day -> task titles), in week order. */
    private fun summarizeWeeklyPlan(body: String): String {
        val root = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return body.trim().ifBlank { "(empty)" }
        val days = root.path("days")
        if (!days.isObject || days.size() == 0) {
            val total = root.path("totalTasks").asInt(0)
            return if (total == 0) "No tasks distributed across this week yet."
            else "$total task(s) queued, but no day-by-day breakdown is available yet."
        }
        val sb = StringBuilder()
        days.fields().forEach { entry ->
            val titles = entry.value.takeIf(JsonNode::isArray)
                ?.joinToString(", ") { it.asText() }
                .orEmpty()
            sb.append(entry.key.replaceFirstChar { it.uppercase() }).append(": ").append(titles).append('\n')
        }
        return sb.toString().trim().ifBlank { "No tasks distributed across this week yet." }
    }

    /** Formats `DailyPlanDto` (focus goal, morning/work/evening blocks, tasksForDay). */
    private fun summarizeDailyPlan(body: String): String {
        val root = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return body.trim().ifBlank { "(empty)" }
        val sb = StringBuilder()
        root.path("focusGoal").takeIf { !it.isMissingNode && !it.isNull && it.asText().isNotBlank() }
            ?.let { sb.append("Focus: ").append(it.asText()).append('\n') }
        val blocks = root.path("blocks")
        if (blocks.isObject) {
            listOf("morning", "work", "evening").forEach { block ->
                val activities = blocks.path(block)
                if (activities.isArray && activities.size() > 0) {
                    val label = block.replaceFirstChar { it.uppercase() }
                    sb.append(label).append(": ")
                        .append(activities.joinToString("; ") { it.asText() })
                        .append('\n')
                }
            }
        }
        val tasks = root.path("tasksForDay").takeIf(JsonNode::isArray)
        if (tasks != null && tasks.size() > 0) {
            sb.append("Tasks: ").append(tasks.joinToString(", ") { it.path("title").asText(it.asText("task")) })
        }
        return sb.toString().trim().ifBlank { "No plan details available yet." }
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
