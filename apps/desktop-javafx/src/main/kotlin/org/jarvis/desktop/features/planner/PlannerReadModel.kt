package org.jarvis.desktop.features.planner

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class PlannerReadModel(
    private val apiClient: ApiClient,
    private val patchClient: PlannerPatchClient = PlannerPatchClient()
) {
    private val objectMapper = jacksonObjectMapper()

    data class TodoTask(
        val id: Long,
        val title: String,
        val description: String?,
        val priority: String,
        val status: String,
        val dueDate: Instant?,
        val tags: List<String>,
        /** Recurrence pattern name (e.g. "DAILY") when this task is itself a recurring template; null/"NONE" otherwise. */
        val recurrenceRule: String?,
        /** Set when this task is a single generated occurrence of a recurring template. */
        val recurrenceSourceTaskId: Long?
    ) {
        /** The recurring template itself (generates future occurrences), not one of its occurrences. */
        val isRecurringTemplate: Boolean =
            recurrenceRule != null && recurrenceRule != "NONE" && recurrenceSourceTaskId == null

        /** A single generated occurrence of a recurring template. */
        val isRecurringOccurrence: Boolean = recurrenceSourceTaskId != null
    }

    /** A plan-mode option offered by the plan-mode selector; `code` matches the backend `PlanMode` enum. */
    data class PlanModeOption(val code: String, val label: String) {
        override fun toString(): String = label
    }

    companion object {
        const val DEFAULT_GENERATE_OCCURRENCE_COUNT = 5

        val PLAN_MODE_OPTIONS: List<PlanModeOption> = listOf(
            PlanModeOption("NORMAL", "Normal"),
            PlanModeOption("RECOVERY", "Recovery"),
            PlanModeOption("DEEP_WORK", "Deep work"),
            PlanModeOption("MINIMUM_VIABLE_DAY", "Minimum day")
        )
    }

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

    /** Reads back the persisted plan-mode selection from `GET /api/v1/planner/plan/mode`; defaults to NORMAL. */
    fun loadPlanMode(): String =
        runCatching { objectMapper.readTree(apiClient.get("/planner/plan/mode")).path("mode").asText("NORMAL") }
            .getOrDefault("NORMAL")

    /** Persists the plan-mode selection via `POST /api/v1/planner/plan/mode`; returns the mode the backend saved. */
    fun setPlanMode(code: String): String {
        val payload = objectMapper.createObjectNode().apply { put("mode", code) }
        val response = apiClient.post("/planner/plan/mode", objectMapper.writeValueAsString(payload))
        return objectMapper.readTree(response).path("mode").asText(code)
    }

    /** Energy/plan-mode ranked task slice from `GET /api/v1/planner/plan/by-mode`; degrades gracefully. */
    fun loadPlanByMode(): BriefResult =
        runCatching { apiClient.get("/planner/plan/by-mode") }.fold(
            onSuccess = { body -> BriefResult.Available(summarizePlanByMode(body)) },
            onFailure = { error -> BriefResult.Unavailable(error.message ?: "endpoint unavailable") }
        )

    /** Formats the `{mode, energy, tasks[]}` payload from `EnergyAwareRanker`. */
    private fun summarizePlanByMode(body: String): String {
        val root = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return body.trim().ifBlank { "(empty)" }
        val tasks = root.path("tasks").takeIf(JsonNode::isArray)
        if (tasks == null || tasks.size() == 0) {
            return "No tasks ranked for this plan mode yet."
        }
        val sb = StringBuilder()
        val mode = root.path("mode").asText("NORMAL")
        val energy = root.path("energy").asText(null)
        sb.append("Mode: ").append(mode)
        if (!energy.isNullOrBlank()) sb.append(" | Energy: ").append(energy)
        sb.append('\n')
        tasks.forEach { task ->
            val title = task.path("title").asText("Untitled task")
            val priority = task.path("priority").asText(null)
            val pressure = task.path("deadlinePressure").asText(null)
            sb.append("• ").append(title)
            val details = listOfNotNull(priority, pressure).filter { it.isNotBlank() }
            if (details.isNotEmpty()) sb.append(" (").append(details.joinToString(", ")).append(')')
            sb.append('\n')
        }
        return sb.toString().trim()
    }

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

    fun createTodo(title: String, description: String?, priority: String, dueDate: Instant? = null): TodoTask {
        val payload = objectMapper.createObjectNode().apply {
            put("title", title.trim())
            description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
            put("priority", priority)
            dueDate?.let { put("dueDate", it.toString()) }
        }
        val response = apiClient.postWithHeaders(
            "/tools/todo/create",
            objectMapper.writeValueAsString(payload),
            idempotencyHeaders()
        )
        return parseTask(objectMapper.readTree(response))
    }

    /**
     * Edits title/description/priority/due date through `POST /api/v1/tools/todo/update`.
     * The backend merges only non-null fields, so a blank [description] or a `null` [dueDate]
     * leaves the existing value in place rather than clearing it.
     */
    fun updateTodo(
        id: Long,
        title: String,
        description: String?,
        priority: String,
        dueDate: Instant?
    ): TodoTask {
        val payload = objectMapper.createObjectNode().apply {
            put("id", id)
            put("title", title.trim())
            description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
            put("priority", priority)
            dueDate?.let { put("dueDate", it.toString()) }
        }
        val response = apiClient.postWithHeaders(
            "/tools/todo/update",
            objectMapper.writeValueAsString(payload),
            idempotencyHeaders()
        )
        return parseTask(objectMapper.readTree(response))
    }

    /** Deletes a task via `DELETE /api/v1/planner/tasks/{id}`. */
    fun deleteTodo(id: Long) {
        apiClient.delete("/planner/tasks/$id")
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

    /** Marks one recurring occurrence skipped via `PATCH /api/v1/planner/tasks/{id}/skip-occurrence`. */
    fun skipOccurrence(id: Long): TodoTask =
        parseTask(objectMapper.readTree(patchClient.patch("/planner/tasks/$id/skip-occurrence")))

    /** Marks one recurring occurrence done via `PATCH /api/v1/planner/tasks/{id}/complete-occurrence`. */
    fun completeOccurrence(id: Long): TodoTask =
        parseTask(objectMapper.readTree(patchClient.patch("/planner/tasks/$id/complete-occurrence")))

    /** Materializes upcoming occurrences via `POST /api/v1/planner/tasks/{id}/generate-next-occurrences`. */
    fun generateNextOccurrences(id: Long, count: Int = DEFAULT_GENERATE_OCCURRENCE_COUNT): List<TodoTask> {
        val response = apiClient.post("/planner/tasks/$id/generate-next-occurrences?count=$count", "{}")
        return objectMapper.readTree(response).map(::parseTask)
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
                ?: emptyList(),
            recurrenceRule = node.stringOrNull("recurrenceRule"),
            recurrenceSourceTaskId = node.longOrNull("recurrenceSourceTaskId")
        )
    }

    private fun idempotencyHeaders(): Map<String, String> {
        return mapOf("X-Idempotency-Key" to UUID.randomUUID().toString())
    }

    private fun JsonNode.stringOrNull(field: String): String? {
        return path(field).asText(null)?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JsonNode.longOrNull(field: String): Long? {
        val node = path(field)
        return if (node.isMissingNode || node.isNull) null else node.asLong()
    }
}
