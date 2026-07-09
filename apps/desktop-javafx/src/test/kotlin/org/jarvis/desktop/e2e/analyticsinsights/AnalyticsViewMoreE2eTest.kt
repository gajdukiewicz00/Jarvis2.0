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
 * Additional E2E journeys for [AnalyticsView] covering branches not exercised
 * by [AnalyticsViewE2eTest]:
 *
 *  - an empty category payload -> pie "No expense data available." placeholder
 *  - an empty trend payload    -> line "No trend data available." placeholder
 *  - a mixed availability fan-out (by-month + trend up, by-category down) that
 *    degrades only the category card while the other two charts still mount,
 *    also exercising the trend axis-label branch.
 *
 * The refresh worker probes by-month, by-category, then trend in order.
 */
class AnalyticsViewMoreE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun buildView(server: MockWebServer): AnalyticsView = E2eFx.onFx {
        val view = AnalyticsView(E2eFx.apiClientFor(server))
        Scene(view)
        view.applyCss()
        view.layout()
        view
    }

    private fun fireRefresh(view: AnalyticsView) = E2eFx.onFx {
        requireNotNull(E2eFx.find<Button>(view)) { "Refresh button not found" }.fire()
    }

    private fun drainRequests(server: MockWebServer, count: Int): List<RecordedRequest> =
        (0 until count).mapNotNull { server.takeRequest(5, TimeUnit.SECONDS) }

    @Test
    fun `empty category and trend payloads render their no-data placeholders`() {
        val server = MockWebServer()
        server.enqueue(json("[]"))                                                  // by-month: empty
        server.enqueue(json("[]"))                                                  // by-category: empty
        server.enqueue(json("""{"title":"Expense trend","labels":[],"values":[]}""")) // trend: empty
        server.start()
        try {
            val view = buildView(server)
            fireRefresh(view)

            E2eFx.waitForFx(description = "no-data placeholders rendered") {
                E2eFx.hasText(view, "No trend data available.")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "No expense data available."), "empty month/category placeholder shown")
                assertTrue(E2eFx.hasText(view, "No trend data available."), "empty trend placeholder shown")
                // Every payload was available (just empty), so no chart mounts and status is Ready.
                assertNull(E2eFx.find<BarChart<*, *>>(view), "empty month payload mounts no bar chart")
                assertNull(E2eFx.find<PieChart>(view), "empty category payload mounts no pie chart")
                assertNull(E2eFx.find<LineChart<*, *>>(view), "empty trend payload mounts no line chart")
                assertTrue(E2eFx.hasText(view, "Ready"), "status is Ready when data is available but empty")
            }

            val requests = drainRequests(server, 3)
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.method == "GET" }, "all three probes are GETs")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a single failing card degrades on its own while the others still render`() {
        val server = MockWebServer()
        server.enqueue(
            json("""[{"period":"Jan","totalAmount":1200.5,"currency":"USD","count":10}]""")   // by-month OK
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))                    // by-category down
        server.enqueue(
            json(
                """{"title":"Expense trend","xAxisLabel":"Month","yAxisLabel":"USD",
                    "labels":["Jan","Feb"],"values":[100.0,200.0]}"""
            )                                                                                   // trend OK w/ axis labels
        )
        server.start()
        try {
            val view = buildView(server)
            fireRefresh(view)

            E2eFx.waitForFx(description = "category card degraded, others rendered") {
                E2eFx.hasText(view, "Временно недоступно")
            }
            E2eFx.onFx {
                // Month + trend charts still mount from their good payloads.
                assertNotNull(E2eFx.find<BarChart<*, *>>(view), "month bar chart still mounts")
                assertNotNull(E2eFx.find<LineChart<*, *>>(view), "trend line chart still mounts")
                // The category pie is absent — its section degraded to a placeholder.
                assertNull(E2eFx.find<PieChart>(view), "failed category card mounts no pie chart")
                // At least one endpoint was up, so the overall status is Ready.
                assertTrue(E2eFx.hasText(view, "Ready"), "overall status Ready with partial availability")
            }

            val requests = drainRequests(server, 3)
            assertEquals(3, requests.size)
            val paths = requests.map { it.path.orEmpty() }
            assertTrue(paths.any { it.contains("/api/v1/analytics/expenses/by-category") }, "category probed; got $paths")
            assertTrue(paths.any { it.contains("/api/v1/analytics/expenses/trend") }, "trend probed; got $paths")
        } finally {
            server.shutdown()
        }
    }
}
