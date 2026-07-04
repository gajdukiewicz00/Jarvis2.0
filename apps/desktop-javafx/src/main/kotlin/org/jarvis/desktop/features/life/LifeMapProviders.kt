package org.jarvis.desktop.features.life

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.agent.command.DesktopActions
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.features.planner.PlannerReadModel
import org.jarvis.desktop.lifemap.LifeMapPanel
import org.slf4j.LoggerFactory

/**
 * Pure-JVM provider bundle for the Life Map panel.
 *
 * <p>Translates calls into planner-service, the desktop-agent active-window
 * probe and memory-service into the snapshot types {@link LifeMapPanel}
 * consumes. Every loader degrades gracefully — a failure produces a
 * snapshot with an explicit degraded reason rather than throwing — so the
 * panel always renders a deterministic, user-readable state.</p>
 */
class LifeMapProviders(
    private val plannerReadModel: PlannerReadModel,
    private val desktopActions: DesktopActions?,
    private val apiClient: ApiClient
) {
    private val log = LoggerFactory.getLogger(LifeMapProviders::class.java)
    private val mapper = jacksonObjectMapper()

    fun loadTasks(): LifeMapPanel.TasksSnapshot? {
        return runCatching {
            val snapshot = plannerReadModel.loadSnapshot()
            val recent = snapshot.tasks
                .filter { it.status != "DONE" && it.status != "CANCELLED" }
                .take(5)
                .map(::formatRecentTask)
            LifeMapPanel.TasksSnapshot(
                open = snapshot.openCount,
                doneToday = snapshot.doneCount,
                recent = recent
            )
        }.onFailure { log.debug("planner snapshot failed: {}", it.message) }
            .getOrNull()
    }

    fun loadActivity(): LifeMapPanel.ActivitySnapshot {
        val actions = desktopActions
            ?: return LifeMapPanel.ActivitySnapshot(
                activeWindowTitle = null,
                degradedReason = "no desktop-agent actions binding (running outside agent process?)"
            )
        return runCatching {
            val outcome = actions.getActiveWindow()
            if (outcome.ok) {
                val title = outcome.output["title"]?.toString()
                LifeMapPanel.ActivitySnapshot(
                    activeWindowTitle = title,
                    degradedReason = null
                )
            } else {
                LifeMapPanel.ActivitySnapshot(
                    activeWindowTitle = null,
                    degradedReason = outcome.errorReason ?: "active-window probe failed"
                )
            }
        }.getOrElse { ex ->
            LifeMapPanel.ActivitySnapshot(
                activeWindowTitle = null,
                degradedReason = "active-window probe threw: ${ex.message}"
            )
        }
    }

    fun loadInsights(): LifeMapPanel.InsightsSnapshot {
        val response = runCatching { apiClient.get("/memory/recent?limit=5") }
            .getOrElse { ex ->
                return LifeMapPanel.InsightsSnapshot(
                    available = false,
                    reason = "memory-service unreachable: ${ex.message}"
                )
            }
        return runCatching {
            val node: JsonNode = mapper.readTree(response)
            val raw = if (node.isArray) node else node.path("items")
            val items = raw.take(5)
                .map { it.path("text").asText("").ifBlank { it.toString() } }
                .filter { it.isNotBlank() }
            LifeMapPanel.InsightsSnapshot(available = true, items = items)
        }.getOrElse { ex ->
            LifeMapPanel.InsightsSnapshot(
                available = false,
                reason = "memory-service response unparsable: ${ex.message}"
            )
        }
    }

    private fun formatRecentTask(task: PlannerReadModel.TodoTask): String {
        val priority = task.priority
        val due = task.dueDate?.toString()?.let { "  due=$it" } ?: ""
        return "[$priority] ${task.title}$due"
    }
}
