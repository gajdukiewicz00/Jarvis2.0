package org.jarvis.desktop.e2e.tabs

import javafx.scene.Node
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.ui.tabs.AnalyticsTab
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Headless UI journeys for [AnalyticsTab]. The tab loads four analytics probes
 * on a daemon thread during construction (and again on refresh), then formats
 * each payload into a read-only TextArea and flips the status label. A
 * [Dispatcher] answers by path so both the constructor load and any refresh see
 * a live backend. No control here opens a modal dialog.
 */
class AnalyticsTabE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val monthJson =
        """[{"period":"Jan","totalAmount":1200.50,"currency":"$","count":10},{"period":"Feb","totalAmount":800.00,"currency":"$","count":8}]"""
    private val categoryJson =
        """[{"category":"Groceries","totalAmount":500.00,"currency":"$","count":5}]"""
    private val timeJson =
        """[{"category":"Work","totalDurationHours":10.5,"activityCount":3,"averageDurationHours":3.5}]"""
    private val calendarJson =
        """{"totalEvents":42,"upcomingEvents":5,"pastEvents":37,"allDayEvents":2}"""

    private fun happyDispatcher(paths: MutableList<String>): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            paths.add(path)
            return when {
                path.contains("/analytics/expenses/by-month") -> json(monthJson)
                path.contains("/analytics/expenses/by-category") -> json(categoryJson)
                path.contains("/analytics/time/summary") -> json(timeJson)
                path.contains("/analytics/calendar/summary") -> json(calendarJson)
                else -> json("[]")
            }
        }
    }

    private fun build(server: MockWebServer): Node =
        E2eFx.onFx { AnalyticsTab(E2eFx.apiClientFor(server)).tab.content }

    @Test
    fun `happy path formats all four analytics panes and reports success`() {
        val server = MockWebServer()
        val paths = CopyOnWriteArrayList<String>()
        server.dispatcher = happyDispatcher(paths)
        server.start()
        try {
            val content = build(server)

            E2eFx.waitForFx(description = "analytics loaded") {
                E2eFx.hasText(content, "Analytics loaded successfully")
            }

            assertTrue(E2eFx.onFx { E2eFx.hasText(content, "Jan") }, "monthly pane should list Jan")
            assertTrue(E2eFx.onFx { E2eFx.hasText(content, "Groceries") }, "category pane should list Groceries")
            assertTrue(E2eFx.onFx { E2eFx.hasText(content, "Work") }, "time pane should list Work category")
            assertTrue(E2eFx.onFx { E2eFx.hasText(content, "Total Events") }, "calendar pane should render its summary")

            assertTrue(
                paths.any { it.contains("/analytics/expenses/by-month") },
                "by-month probed; got $paths"
            )
            assertTrue(
                paths.any { it.contains("/analytics/calendar/summary") },
                "calendar probed; got $paths"
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh reloads all analytics panes`() {
        val server = MockWebServer()
        val paths = CopyOnWriteArrayList<String>()
        server.dispatcher = happyDispatcher(paths)
        server.start()
        try {
            val tab = E2eFx.onFx { AnalyticsTab(E2eFx.apiClientFor(server)) }
            val content = E2eFx.onFx { tab.tab.content }
            E2eFx.waitForFx(description = "initial load") { E2eFx.hasText(content, "loaded successfully") }

            val before = paths.size
            E2eFx.onFx { tab.refresh() }

            E2eFx.waitForFx(description = "reload issued more probes") { paths.size >= before + 4 }
            assertTrue(paths.size >= before + 4, "refresh should re-probe all four endpoints")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `server errors mark every pane as failed`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(500).setBody("boom")
        }
        server.start()
        try {
            val content = build(server)

            E2eFx.waitForFx(description = "analytics failure banner") {
                E2eFx.hasText(content, "Analytics failed in 4 pane(s)")
            }
            assertTrue(E2eFx.onFx { E2eFx.hasText(content, "Error:") }, "panes should show an error line")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty month payload renders the no-data placeholder`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/analytics/expenses/by-month") -> json("[]")
                    path.contains("/analytics/expenses/by-category") -> json(categoryJson)
                    path.contains("/analytics/time/summary") -> json(timeJson)
                    path.contains("/analytics/calendar/summary") -> json(calendarJson)
                    else -> json("[]")
                }
            }
        }
        server.start()
        try {
            val content = build(server)

            E2eFx.waitForFx(description = "no-data placeholder") {
                E2eFx.hasText(content, "No expense data available")
            }
            assertTrue(
                E2eFx.onFx { E2eFx.hasText(content, "Analytics loaded successfully") },
                "overall status is still success when only one pane is empty"
            )
        } finally {
            server.shutdown()
        }
    }
}
