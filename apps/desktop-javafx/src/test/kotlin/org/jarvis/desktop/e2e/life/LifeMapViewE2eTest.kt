package org.jarvis.desktop.e2e.life

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.life.LifeMapView
import org.jarvis.desktop.features.planner.PlannerReadModel
import org.jarvis.desktop.lifemap.LifeMapClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections

/**
 * True UI end-to-end journeys for [LifeMapView] — the canonical Life route.
 *
 * <p>Each test constructs the REAL view against a [MockWebServer] standing in
 * for life-tracker + the api-gateway, lets the panel's auto-poll (and a real
 * user-fired Refresh) load the daily rollups, then asserts BOTH that the
 * life-map summary probe reached the backend AND that the visible per-tab
 * widgets (Sleep / Finances / Tasks / Activity / Health / Memory insights
 * TextAreas) rendered the parsed data.</p>
 *
 * <p>The panel wraps every section body in a skinned [ScrollPane] inside a
 * [TabPane], so the section widgets are not reachable through
 * `childrenUnmodifiable` without a live scene. We instead drill straight into
 * the real content node of each [Tab] — the exact widget a user sees when they
 * select that tab — via [tabBody].</p>
 */
class LifeMapViewE2eTest {

    /** The VBox body a user sees inside the named tab (unwrapping the ScrollPane wrapper). */
    private fun tabBody(view: Node, title: String): Node {
        val tabPane = requireNotNull(E2eFx.find<TabPane>(view)) { "LifeMapView should own a TabPane" }
        val tab: Tab = tabPane.tabs.first { it.text == title }
        return (tab.content as ScrollPane).content
    }

    private fun buildView(server: MockWebServer): LifeMapView {
        val rootUrl = server.url("/").toString().removeSuffix("/")
        return LifeMapView(
            apiClient = E2eFx.apiClientFor(server),
            liveFeed = AgentLiveFeed(),
            lifeMapClient = LifeMapClient(rootUrl),
            plannerReadModel = PlannerReadModel(E2eFx.apiClientFor(server)),
            desktopActions = null,
            userIdProvider = { "e2e-user" }
        )
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `daily rollups load and render across the life-map section tabs`() {
        val server = MockWebServer()
        val paths = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                paths.add(path)
                return when {
                    path.contains("/life-map/summary") -> jsonResponse(SUMMARY_JSON)
                    path.contains("/life-map/warnings") -> jsonResponse(WARNINGS_JSON)
                    path.contains("/life-map/activity") -> jsonResponse(ACTIVITY_JSON)
                    path.contains("/tools/todo/list") -> jsonResponse(TODO_JSON)
                    path.contains("/memory/recent") -> jsonResponse(MEMORY_JSON)
                    else -> jsonResponse("[]")
                }
            }
        }
        server.start()
        try {
            val view = E2eFx.onFx { buildView(server) }

            // Auto-poll (scheduled at 0 delay) loads the Sleep rollup from the summary.
            E2eFx.waitForFx(description = "sleep rollup rendered") {
                E2eFx.hasText(tabBody(view, "Sleep"), "Last night: 7")
            }

            // Real user gesture: fire the Finances tab's Refresh button to reload.
            E2eFx.onFx {
                E2eFx.findAll<Button>(tabBody(view, "Finances"))
                    .first { it.text == "Refresh" }
                    .fire()
            }

            // Every section widget reflects the parsed daily rollup.
            E2eFx.waitForFx(description = "finance rollup rendered") {
                E2eFx.hasText(tabBody(view, "Finances"), "Income: 1500")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(tabBody(view, "Finances"), "Expense: 42"), "expense should render")
                assertTrue(E2eFx.hasText(tabBody(view, "Tasks"), "Open: 1"), "open task count should render")
                assertTrue(E2eFx.hasText(tabBody(view, "Tasks"), "Buy milk"), "recent task should render")
                assertTrue(E2eFx.hasText(tabBody(view, "Activity"), "IntelliJ"), "activity entry should render")
                assertTrue(
                    E2eFx.hasText(tabBody(view, "Health"), "Vision incidents (24h): 3"),
                    "health vision incidents should render"
                )
                assertTrue(E2eFx.hasText(tabBody(view, "Health"), "SEDENTARY"), "proactive warning should render")
                assertTrue(
                    E2eFx.hasText(tabBody(view, "Memory insights"), "Recalled: meeting at 3pm"),
                    "memory insight should render"
                )
            }

            // Backend actually received the life-map summary probe.
            assertTrue(
                paths.any { it.contains("/api/v1/life-map/summary") && it.contains("userId=e2e-user") },
                "life-map summary should have been requested; saw: $paths"
            )

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `summary backend 500 degrades sections honestly without crashing`() {
        val server = MockWebServer()
        val paths = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                paths.add(path)
                return when {
                    // life-tracker summary is down — LifeMapClient returns null on non-2xx.
                    path.contains("/life-map/summary") -> MockResponse().setResponseCode(500).setBody("boom")
                    path.contains("/life-map/warnings") -> jsonResponse("[]")
                    path.contains("/life-map/activity") -> jsonResponse("[]")
                    else -> jsonResponse("[]")
                }
            }
        }
        server.start()
        try {
            val view = E2eFx.onFx { buildView(server) }

            E2eFx.waitForFx(description = "finance section degraded") {
                E2eFx.hasText(tabBody(view, "Finances"), "DEGRADED")
            }
            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(tabBody(view, "Finances"), "life-tracker /life-map/summary not reachable"),
                    "finance section should explain the summary endpoint is unreachable"
                )
                assertTrue(
                    E2eFx.hasText(tabBody(view, "Sleep"), "Sleep data not available"),
                    "sleep section should degrade to the not-available message"
                )
                // The panel never crashed: a placeholder still renders in Home.
                assertFalse(E2eFx.hasText(tabBody(view, "Finances"), "Income:"), "no finance figures on 500")
            }

            assertTrue(
                paths.any { it.contains("/api/v1/life-map/summary") },
                "life-map summary should still have been probed; saw: $paths"
            )

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    companion object {
        private val SUMMARY_JSON = """
            {
              "sleepHours": 7.4,
              "financeIncome": "1500",
              "financeExpense": "42.50",
              "financeBudget": "300",
              "visionIncidentsLast24h": 3,
              "jarvisLiveFeedCountLast24h": 5,
              "totalTrackedSeconds": 3600
            }
        """.trimIndent()

        private val WARNINGS_JSON = """
            [
              {"severity": "HIGH", "code": "SEDENTARY", "message": "You've been sitting 3h"}
            ]
        """.trimIndent()

        private val ACTIVITY_JSON = """
            [
              {"category": "Coding", "appName": "IntelliJ", "durationSeconds": 600}
            ]
        """.trimIndent()

        private val TODO_JSON = """
            [
              {"id": 1, "title": "Buy milk", "priority": "HIGH", "status": "TODO"},
              {"id": 2, "title": "Ship report", "priority": "MEDIUM", "status": "DONE"}
            ]
        """.trimIndent()

        private val MEMORY_JSON = """
            {"items": [{"text": "Recalled: meeting at 3pm"}]}
        """.trimIndent()
    }
}
