package org.jarvis.desktop.e2e.analyticsinsights

import javafx.scene.Scene
import javafx.scene.chart.BarChart
import javafx.scene.chart.LineChart
import javafx.scene.chart.PieChart
import javafx.scene.control.Button
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.analytics.AnalyticsView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * True headless UI E2E journeys for [AnalyticsView].
 *
 * Each test constructs the REAL view against a [MockWebServer] standing in for
 * the api-gateway, fires the real "Refresh" button (a user gesture), then
 * asserts BOTH that the visible scene graph reacted (charts mounted with data,
 * status pills, honest placeholders) AND that the backend received the expected
 * GET requests on the analytics expense endpoints.
 *
 * The refresh worker issues its three probes sequentially on a single thread,
 * so MockWebServer FIFO delivery matches enqueue order:
 *   1. GET /api/v1/analytics/expenses/by-month
 *   2. GET /api/v1/analytics/expenses/by-category
 *   3. GET /api/v1/analytics/expenses/trend?period=month
 */
class AnalyticsViewE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** Build the real view on the FX thread and force skin/layout so the
     *  ScrollPane content (charts, cards) is reachable via the scene graph. */
    private fun buildView(server: MockWebServer): AnalyticsView = E2eFx.onFx {
        val view = AnalyticsView(E2eFx.apiClientFor(server))
        Scene(view)
        view.applyCss()
        view.layout()
        view
    }

    private fun fireRefresh(view: AnalyticsView) = E2eFx.onFx {
        val button = requireNotNull(E2eFx.find<Button>(view)) { "Refresh button not found" }
        button.fire()
    }

    private fun drainRequests(server: MockWebServer, count: Int): List<RecordedRequest> =
        (0 until count).mapNotNull { server.takeRequest(5, TimeUnit.SECONDS) }

    @Test
    fun `happy path renders bar, pie and line charts from live analytics data`() {
        val server = MockWebServer()
        server.enqueue(
            json(
                """
                [
                  {"period": "Jan", "totalAmount": 1200.5, "currency": "USD", "count": 10},
                  {"period": "Feb", "totalAmount": 800.0, "currency": "USD", "count": 8}
                ]
                """.trimIndent()
            )
        )
        server.enqueue(
            json(
                """
                [
                  {"category": "Groceries", "totalAmount": 500.0, "currency": "USD", "count": 5},
                  {"category": "Transport", "totalAmount": 300.0, "currency": "USD", "count": 3}
                ]
                """.trimIndent()
            )
        )
        server.enqueue(
            json(
                """
                {
                  "title": "Expense trend",
                  "xAxisLabel": "Month",
                  "yAxisLabel": "USD",
                  "labels": ["Jan", "Feb", "Mar"],
                  "values": [100.0, 200.0, 150.0]
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val view = buildView(server)
            fireRefresh(view)

            E2eFx.waitForFx(description = "analytics status Ready") { E2eFx.hasText(view, "Ready") }

            // Bar chart mounted with the two monthly points.
            val monthSeries = E2eFx.onFx {
                E2eFx.find<BarChart<*, *>>(view)?.data?.firstOrNull()?.data?.size ?: -1
            }
            assertEquals(2, monthSeries, "monthly bar chart should hold 2 points")

            // Pie chart mounted with two category slices; slice names carry the label.
            val pieNames = E2eFx.onFx {
                E2eFx.find<PieChart>(view)?.data?.map { it.name } ?: emptyList()
            }
            assertEquals(2, pieNames.size, "pie chart should hold 2 category slices")
            assertTrue(
                pieNames.any { it.startsWith("Groceries") },
                "pie slice should be labelled with the category name; got $pieNames"
            )

            // Line chart mounted with the three trend points.
            val trendPoints = E2eFx.onFx {
                E2eFx.find<LineChart<*, *>>(view)?.data?.firstOrNull()?.data?.size ?: -1
            }
            assertEquals(3, trendPoints, "trend line chart should hold 3 points")

            // Per-section pills flipped to OK and the Loading placeholder is gone.
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "OK") }, "section pills should read OK")
            assertTrue(
                E2eFx.onFx { !E2eFx.hasText(view, "Loading…") },
                "the Loading placeholder should be replaced by charts"
            )

            // Backend received the three expense probes as GETs.
            val requests = drainRequests(server, 3)
            assertEquals(3, requests.size, "expected 3 analytics probes")
            assertTrue(requests.all { it.method == "GET" }, "all analytics probes are GETs")
            val paths = requests.map { it.path.orEmpty() }
            assertTrue(paths.any { it.contains("/api/v1/analytics/expenses/by-month") }, "by-month probed; got $paths")
            assertTrue(paths.any { it.contains("/api/v1/analytics/expenses/by-category") }, "by-category probed; got $paths")
            assertTrue(paths.any { it.contains("/api/v1/analytics/expenses/trend") }, "trend probed; got $paths")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `all endpoints down degrades every card to an honest unavailable placeholder`() {
        val server = MockWebServer()
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("boom")) }
        server.start()
        try {
            val view = buildView(server)
            fireRefresh(view)

            E2eFx.waitForFx(description = "analytics unavailable placeholder") {
                E2eFx.hasText(view, "Временно недоступно")
            }

            // No chart is mounted when every probe fails.
            assertNull(E2eFx.onFx { E2eFx.find<BarChart<*, *>>(view) }, "bar chart must not mount on 500")
            assertNull(E2eFx.onFx { E2eFx.find<PieChart>(view) }, "pie chart must not mount on 500")
            assertNull(E2eFx.onFx { E2eFx.find<LineChart<*, *>>(view) }, "line chart must not mount on 500")

            // Overall status pill reflects the fully-degraded state.
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Unavailable") }, "status pill should read Unavailable")

            val requests = drainRequests(server, 3)
            assertEquals(3, requests.size, "the view still probes all three endpoints")
            assertTrue(requests.all { it.method == "GET" }, "all probes are GETs")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty month payload shows no-data placeholder while other cards still render`() {
        val server = MockWebServer()
        server.enqueue(json("[]")) // by-month: available but empty
        server.enqueue(
            json("""[{"category": "Groceries", "totalAmount": 500.0, "currency": "USD", "count": 5}]""")
        )
        server.enqueue(
            json("""{"title": "Expense trend", "labels": ["Jan"], "values": [100.0]}""")
        )
        server.start()
        try {
            val view = buildView(server)
            fireRefresh(view)

            E2eFx.waitForFx(description = "analytics no-data placeholder") {
                E2eFx.hasText(view, "No expense data available.")
            }

            // Month card degraded to a no-data placeholder (no bar chart), but the
            // pie card for the non-empty category payload still mounted.
            assertNull(E2eFx.onFx { E2eFx.find<BarChart<*, *>>(view) }, "empty month payload mounts no bar chart")
            assertNotNull(E2eFx.onFx { E2eFx.find<PieChart>(view) }, "category card still renders its pie chart")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Ready") }, "overall status is Ready since data is available")

            val requests = drainRequests(server, 3)
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.method == "GET" })
        } finally {
            server.shutdown()
        }
    }
}
