package org.jarvis.desktop.lifemap

import com.fasterxml.jackson.databind.ObjectMapper
import org.jarvis.agent.feed.AgentLiveFeed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the formatter contracts that drive each Life Map tab. Pure JVM —
 * no JavaFX, no HTTP — so the tests run in CI without a display.
 */
class LifeMapPanelFormattersTest {

    private val mapper = ObjectMapper()
    private val client = LifeMapClient(baseUrl = "http://invalid.localhost:1")
    private val feed = AgentLiveFeed()

    private fun panel(
        tasksProvider: () -> LifeMapPanel.TasksSnapshot? = { null },
        activityProvider: () -> LifeMapPanel.ActivitySnapshot? = { null },
        insightsProvider: () -> LifeMapPanel.InsightsSnapshot? = { null }
    ): LifeMapPanel = LifeMapPanel(
        client = client,
        userId = "owner",
        liveFeed = feed,
        tasksProvider = tasksProvider,
        activityProvider = activityProvider,
        insightsProvider = insightsProvider
    )

    @Test
    fun `finance tab renders income expense and budget when summary present`() {
        val summary = mapper.readTree(
            """{"financeIncome":"3000","financeExpense":"1200","financeBudget":"5000"}"""
        )
        val text = panel().formatFinances(summary)

        assertTrue(text.contains("Income: 3000"))
        assertTrue(text.contains("Expense: 1200"))
        assertTrue(text.contains("Budget: 5000"))
    }

    @Test
    fun `finance tab is explicitly degraded when life-tracker summary is null`() {
        val text = panel().formatFinances(null)

        assertTrue(text.startsWith("DEGRADED"))
        assertTrue(text.contains("life-tracker"))
    }

    @Test
    fun `tasks tab uses planner snapshot when provider returns one`() {
        val plannerSnapshot = LifeMapPanel.TasksSnapshot(
            open = 4,
            doneToday = 2,
            recent = listOf("[HIGH] Ship Life Map", "[MEDIUM] Update README")
        )

        val text = panel(tasksProvider = { plannerSnapshot }).formatTasks(null, plannerSnapshot)

        assertTrue(text.contains("Open: 4"))
        assertTrue(text.contains("Done today: 2"))
        assertTrue(text.contains("Ship Life Map"))
        assertTrue(text.contains("Update README"))
    }

    @Test
    fun `tasks tab falls back to life-tracker counts when planner snapshot is null`() {
        val summary = mapper.readTree("""{"tasksOpen":7,"tasksDoneToday":1}""")

        val text = panel().formatTasks(summary, null)

        assertTrue(text.contains("Open: 7"))
        assertTrue(text.contains("Done today: 1"))
    }

    @Test
    fun `tasks tab is degraded when both planner and life-tracker are absent`() {
        val text = panel().formatTasks(null, null)

        assertTrue(text.startsWith("DEGRADED"))
        assertTrue(text.contains("planner-service"))
    }

    @Test
    fun `activity tab renders the desktop-agent active window when supplied`() {
        val agent = LifeMapPanel.ActivitySnapshot(activeWindowTitle = "IDEA – LifeMapPanel.kt")

        val text = panel(activityProvider = { agent }).formatActivity(null, null, agent)

        assertTrue(text.contains("Active window: IDEA – LifeMapPanel.kt"))
        assertTrue(!text.contains("DEGRADED — desktop-agent"))
    }

    @Test
    fun `activity tab surfaces the active-window degraded reason when the probe fails`() {
        val agent = LifeMapPanel.ActivitySnapshot(
            activeWindowTitle = null,
            degradedReason = "xdotool exit=1"
        )

        val text = panel(activityProvider = { agent }).formatActivity(null, null, agent)

        assertTrue(text.contains("Active window: (no focused window)"))
        assertTrue(text.contains("DEGRADED — xdotool exit=1"))
    }

    @Test
    fun `activity tab is degraded when no agent provider is wired`() {
        val text = panel().formatActivity(null, null, null)

        assertTrue(text.contains("DEGRADED — desktop-agent active-window provider not wired"))
    }

    @Test
    fun `health tab is explicitly degraded by default`() {
        val text = panel().formatHealth(null, null, LifeMapPanel.HEALTH_DEGRADED)

        assertTrue(text.contains("Backend: DEGRADED"))
        assertTrue(text.contains("Phase 12"))
    }

    @Test
    fun `memory insights tab renders highlights when provider returns items`() {
        val snapshot = LifeMapPanel.InsightsSnapshot(
            available = true,
            items = listOf("Owner mentioned migrating CV.", "Pending PR on Life route.")
        )

        val text = panel(insightsProvider = { snapshot }).formatInsights(snapshot)

        assertTrue(text.contains("Owner mentioned migrating CV."))
        assertTrue(text.contains("Pending PR on Life route."))
    }

    @Test
    fun `memory insights tab is degraded when provider reports unavailable`() {
        val snapshot = LifeMapPanel.InsightsSnapshot(
            available = false,
            reason = "memory-service unreachable: connection refused"
        )

        val text = panel().formatInsights(snapshot)

        assertTrue(text.startsWith("DEGRADED"))
        assertTrue(text.contains("connection refused"))
    }

    @Test
    fun `default health snapshot exposes a stable DEGRADED contract`() {
        assertEquals("DEGRADED", LifeMapPanel.HEALTH_DEGRADED.state)
        assertTrue(LifeMapPanel.HEALTH_DEGRADED.detail!!.contains("Phase 12"))
    }
}
