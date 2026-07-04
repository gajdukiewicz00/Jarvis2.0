package org.jarvis.desktop.lifemap

import javafx.application.Platform
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextArea
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.jarvis.agent.feed.AgentLiveFeed
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * JavaFX shell route for the desktop "Life Map" panel.
 *
 * <p>Each section maps to a discrete provider (see {@link LifeMapProviders})
 * so the panel renders real data when the backing service is reachable and
 * an explicit DEGRADED state otherwise. Providers default to no-ops in
 * tests; in the real shell they are wired to life-tracker, planner-service,
 * the desktop agent's active-window probe and memory-service.</p>
 */
class LifeMapPanel(
    private val client: LifeMapClient,
    private val userId: String,
    private val liveFeed: AgentLiveFeed,
    private val refreshSeconds: Long = 15,
    private val tasksProvider: () -> TasksSnapshot? = { null },
    private val activityProvider: () -> ActivitySnapshot? = { null },
    private val insightsProvider: () -> InsightsSnapshot? = { null },
    private val healthProvider: () -> HealthSnapshot = { HEALTH_DEGRADED }
) {
    private val log = LoggerFactory.getLogger(LifeMapPanel::class.java)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jarvis-life-map-refresher").also { it.isDaemon = true }
    }
    private val sleep = SectionViewModel("Sleep")
    private val finances = SectionViewModel("Finances")
    private val tasks = SectionViewModel("Tasks")
    private val home = SectionViewModel("Home")
    private val activity = SectionViewModel("Activity")
    private val health = SectionViewModel("Health")
    private val insights = SectionViewModel("Memory insights")

    fun build(): Node {
        val tabPane = TabPane(
            tab("Sleep", sectionView(sleep, "Last night, sleep stages, recovery score.")),
            tab("Finances", sectionView(finances, "Today's income / expense / budget headroom.")),
            tab("Tasks", sectionView(tasks, "Open tasks, completed today.")),
            tab("Home", sectionView(home, "Smart-home device states (Phase 12).")),
            tab("Activity", sectionView(activity, "Active window + time spent by category.")),
            tab("Health", sectionView(health, "Health backend status.")),
            tab("Memory insights", sectionView(insights, "Recent memory-service highlights.")),
            tab("Jarvis live feed", liveFeedView())
        )
        startPolling()
        return tabPane
    }

    fun stop() {
        scheduler.shutdownNow()
    }

    // -------- per-section view --------

    private fun sectionView(vm: SectionViewModel, hint: String): Node {
        val title = Label(vm.title).apply {
            style = "-fx-font-size: 18; -fx-font-weight: bold;"
        }
        val hintLabel = Label(hint).apply {
            style = "-fx-text-fill: #777;"
        }
        val content = TextArea("loading…").apply {
            isEditable = false
            isWrapText = true
            VBox.setVgrow(this, Priority.ALWAYS)
        }
        val refreshBtn = Button("Refresh").apply {
            setOnAction { refreshAll() }
        }
        vm.snapshot.addListener { _, _, value ->
            Platform.runLater { content.text = value ?: "no data yet" }
        }
        val box = VBox(8.0, title, hintLabel, content, HBox(refreshBtn).apply {
            alignment = Pos.CENTER_RIGHT
        })
        box.padding = Insets(12.0)
        return ScrollPane(box).apply { isFitToWidth = true }
    }

    private fun liveFeedView(): Node {
        val title = Label("Jarvis live feed").apply {
            style = "-fx-font-size: 18; -fx-font-weight: bold;"
        }
        val content = TextArea().apply {
            isEditable = false
            isWrapText = true
            VBox.setVgrow(this, Priority.ALWAYS)
        }
        renderFeed(content)
        liveFeed.subscribe { Platform.runLater { renderFeed(content) } }
        val box = VBox(8.0, title, content)
        box.padding = Insets(12.0)
        return ScrollPane(box).apply { isFitToWidth = true }
    }

    private fun renderFeed(area: TextArea) {
        val sb = StringBuilder()
        for (event in liveFeed.recent(limit = 100)) {
            sb.append(event.occurredAt).append("  ").append(event.type)
              .append("  [").append(event.severity).append("]\n  ")
              .append(event.message ?: "").append("\n")
        }
        area.text = sb.toString().ifEmpty { "no events yet" }
    }

    // -------- polling --------

    private fun startPolling() {
        scheduler.scheduleAtFixedRate(
            { runCatching { refreshAll() }.onFailure { log.debug("life-map refresh failed: {}", it.message) } },
            0L, refreshSeconds, TimeUnit.SECONDS
        )
    }

    private fun refreshAll() {
        val today = LocalDate.now()
        val summary = client.fetchSummary(userId, today)
        val warnings = client.fetchWarnings(userId, today)
        val activityNode = client.fetchActivity(userId, today)
        sleep.snapshot.set(formatSleep(summary))
        finances.snapshot.set(formatFinances(summary))
        tasks.snapshot.set(formatTasks(summary, runCatching { tasksProvider() }.getOrNull()))
        home.snapshot.set("Smart-home wiring lands in Phase 12.")
        activity.snapshot.set(formatActivity(summary, activityNode, runCatching { activityProvider() }.getOrNull()))
        health.snapshot.set(formatHealth(summary, warnings, runCatching { healthProvider() }.getOrElse { HEALTH_DEGRADED }))
        insights.snapshot.set(formatInsights(runCatching { insightsProvider() }.getOrNull()))
    }

    internal fun formatSleep(summary: com.fasterxml.jackson.databind.JsonNode?): String {
        val hours = summary?.get("sleepHours")?.asDouble(-1.0) ?: -1.0
        return if (hours > 0) "Last night: %.1f h".format(hours)
        else "Sleep data not available (Phase 12 wires Google Fit / Health Connect)."
    }

    internal fun formatFinances(summary: com.fasterxml.jackson.databind.JsonNode?): String {
        if (summary == null) return "DEGRADED — life-tracker /life-map/summary not reachable."
        val income = summary.get("financeIncome")?.asText("0") ?: "0"
        val expense = summary.get("financeExpense")?.asText("0") ?: "0"
        val budget = summary.get("financeBudget")?.asText("—") ?: "—"
        return "Income: $income\nExpense: $expense\nBudget: $budget"
    }

    internal fun formatTasks(
        summary: com.fasterxml.jackson.databind.JsonNode?,
        plannerSnapshot: TasksSnapshot?
    ): String {
        if (plannerSnapshot != null) {
            val sb = StringBuilder()
            sb.append("Open: ").append(plannerSnapshot.open).append('\n')
            sb.append("Done today: ").append(plannerSnapshot.doneToday).append('\n')
            if (plannerSnapshot.recent.isNotEmpty()) {
                sb.append("\nRecent:\n")
                for (line in plannerSnapshot.recent) {
                    sb.append("  • ").append(line).append('\n')
                }
            }
            return sb.toString()
        }
        // Fall back to life-tracker aggregate, if any.
        if (summary == null || (summary.get("tasksOpen") == null && summary.get("tasksDoneToday") == null)) {
            return "DEGRADED — planner-service unavailable, no task counts in life-map summary."
        }
        val open = summary.get("tasksOpen")?.asInt(0) ?: 0
        val done = summary.get("tasksDoneToday")?.asInt(0) ?: 0
        return "Open: $open\nDone today: $done"
    }

    internal fun formatActivity(
        summary: com.fasterxml.jackson.databind.JsonNode?,
        activity: com.fasterxml.jackson.databind.JsonNode?,
        agent: ActivitySnapshot?
    ): String {
        val sb = StringBuilder()
        if (agent != null) {
            sb.append("Active window: ")
                .append(agent.activeWindowTitle?.takeIf { it.isNotBlank() } ?: "(no focused window)")
                .append('\n')
            if (!agent.degradedReason.isNullOrBlank()) {
                sb.append("Active window status: DEGRADED — ").append(agent.degradedReason).append('\n')
            }
        } else {
            sb.append("Active window: DEGRADED — desktop-agent active-window provider not wired.\n")
        }
        val total = summary?.get("totalTrackedSeconds")?.asLong(0L) ?: 0L
        val byCategory = summary?.get("secondsByCategory")
        sb.append("Total tracked: ").append(total / 60).append(" min\n")
        byCategory?.fields()?.forEachRemaining { e ->
            sb.append("  ").append(e.key).append(": ").append(e.value.asLong() / 60).append(" min\n")
        }
        if (activity != null && activity.isArray && activity.size() > 0) {
            sb.append("\nRecent entries:\n")
            for (i in 0 until minOf(activity.size(), 10)) {
                val n = activity[i]
                sb.append("  ").append(n.get("category")?.asText("?")).append("  ")
                    .append(n.get("appName")?.asText("?")).append("  ")
                    .append(n.get("durationSeconds")?.asLong(0)).append("s\n")
            }
        }
        return sb.toString()
    }

    internal fun formatHealth(
        summary: com.fasterxml.jackson.databind.JsonNode?,
        warnings: com.fasterxml.jackson.databind.JsonNode?,
        snapshot: HealthSnapshot
    ): String {
        val sb = StringBuilder()
        sb.append("Backend: ").append(snapshot.state)
        if (!snapshot.detail.isNullOrBlank()) {
            sb.append(" — ").append(snapshot.detail)
        }
        sb.append('\n')
        sb.append("Vision incidents (24h): ").append(summary?.get("visionIncidentsLast24h")?.asInt(0) ?: 0).append('\n')
        sb.append("Jarvis live-feed events (24h): ").append(summary?.get("jarvisLiveFeedCountLast24h")?.asInt(0) ?: 0).append('\n')
        if (warnings != null && warnings.isArray && warnings.size() > 0) {
            sb.append("\nProactive warnings:\n")
            for (i in 0 until warnings.size()) {
                val w = warnings[i]
                sb.append("  • ").append(w.get("severity")?.asText("?"))
                    .append("  ").append(w.get("code")?.asText("?")).append('\n')
                sb.append("    ").append(w.get("message")?.asText("")).append('\n')
            }
        }
        return sb.toString()
    }

    internal fun formatInsights(snapshot: InsightsSnapshot?): String {
        if (snapshot == null || !snapshot.available) {
            val reason = snapshot?.reason?.takeIf { it.isNotBlank() }
                ?: "memory-service insights provider not wired."
            return "DEGRADED — $reason"
        }
        if (snapshot.items.isEmpty()) {
            return "No memory highlights yet."
        }
        val sb = StringBuilder("Recent highlights:\n")
        for (line in snapshot.items) {
            sb.append("  • ").append(line).append('\n')
        }
        return sb.toString()
    }

    private fun tab(title: String, body: Node) = Tab(title, body).apply { isClosable = false }

    private class SectionViewModel(val title: String) {
        val snapshot: SimpleObjectProperty<String> = SimpleObjectProperty<String>(null)
    }

    /** Tasks snapshot from planner-service (or any task source). */
    data class TasksSnapshot(
        val open: Int,
        val doneToday: Int,
        val recent: List<String> = emptyList()
    )

    /** Activity snapshot from the desktop agent (active window + categories). */
    data class ActivitySnapshot(
        val activeWindowTitle: String?,
        val degradedReason: String? = null
    )

    /** Memory-service insights snapshot. */
    data class InsightsSnapshot(
        val available: Boolean,
        val reason: String? = null,
        val items: List<String> = emptyList()
    )

    /** Health backend status. */
    data class HealthSnapshot(
        val state: String,
        val detail: String? = null
    )

    companion object {
        val HEALTH_DEGRADED: HealthSnapshot = HealthSnapshot(
            state = "DEGRADED",
            detail = "no health backend wired (Phase 12 will integrate Google Fit / Health Connect)"
        )
    }
}
