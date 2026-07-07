package org.jarvis.desktop.e2e.analyticsinsights

import javafx.scene.Scene
import javafx.scene.control.Button
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.insights.InsightsView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * True headless UI E2E journeys for [InsightsView].
 *
 * Constructs the REAL Analytics Insights panel against a [MockWebServer], fires
 * the real "Refresh" button, then asserts BOTH the visible scene graph (day-score
 * gauge, forecast rows, insight cards, report key/value rows, honest
 * "Временно недоступно" fallbacks) AND that the backend received the four insight
 * probes as GETs.
 *
 * The refresh worker probes sequentially, so MockWebServer FIFO delivery matches
 * enqueue order:
 *   1. GET /api/v1/analytics/insights
 *   2. GET /api/v1/analytics/insights/day-score
 *   3. GET /api/v1/analytics/insights/forecast
 *   4. GET /api/v1/analytics/insights/report
 */
class InsightsViewE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun buildView(server: MockWebServer): InsightsView = E2eFx.onFx {
        val view = InsightsView(E2eFx.apiClientFor(server))
        Scene(view)
        view.applyCss()
        view.layout()
        view
    }

    private fun fireRefresh(view: InsightsView) = E2eFx.onFx {
        val button = requireNotNull(E2eFx.find<Button>(view)) { "Refresh button not found" }
        button.fire()
    }

    private fun drainRequests(server: MockWebServer, count: Int): List<RecordedRequest> =
        (0 until count).mapNotNull { server.takeRequest(5, TimeUnit.SECONDS) }

    private val insightsArray = """
        [
          {"title": "High spending detected", "detail": "20% above average", "severity": "WARN"},
          {"title": "Budget on track", "detail": "Healthy pace", "severity": "INFO"}
        ]
    """.trimIndent()

    private val dayScoreObject = """
        {"score": 82, "grade": "B", "components": {"spendingControl": 40, "activity": 42}}
    """.trimIndent()

    private val forecastObject = """
        {"month": "2026-07", "dayOfMonth": 7, "daysInMonth": 31,
         "spentSoFar": 210.5, "projectedMonthEnd": 930.0, "dailyRate": 30.07}
    """.trimIndent()

    private val reportObject = """
        {"period": "weekly", "totalEvents": 42, "summary": "All good"}
    """.trimIndent()

    @Test
    fun `happy path renders day-score, forecast, insight cards and report`() {
        val server = MockWebServer()
        server.enqueue(json(insightsArray))
        server.enqueue(json(dayScoreObject))
        server.enqueue(json(forecastObject))
        server.enqueue(json(reportObject))
        server.start()
        try {
            val view = buildView(server)
            fireRefresh(view)

            E2eFx.waitForFx(description = "insights day-score rendered") { E2eFx.hasText(view, "82/100") }

            E2eFx.onFx {
                // Day-score card: big number + grade pill.
                assertTrue(E2eFx.hasText(view, "82/100"), "day-score value rendered")
                assertTrue(E2eFx.hasText(view, "Grade B"), "day-score grade pill rendered")

                // Forecast card: month-progress line and spend line (locale-safe substrings).
                assertTrue(E2eFx.hasText(view, "Day 7 of 31"), "forecast day-of-month line rendered")
                assertTrue(E2eFx.hasText(view, "Spent so far:"), "forecast spend line rendered")
                assertTrue(E2eFx.hasText(view, "Month: 2026-07"), "forecast month caption rendered")

                // Insight cards: title + severity pill.
                assertTrue(E2eFx.hasText(view, "High spending detected"), "insight card title rendered")
                assertTrue(E2eFx.hasText(view, "WARN"), "insight severity pill rendered")

                // Report card: generic labelled key/value rows.
                assertTrue(E2eFx.hasText(view, "All good"), "report summary value rendered")
                assertTrue(E2eFx.hasText(view, "Total events"), "report labelized key rendered")

                // Overall status flipped to Ready and Loading placeholders are gone.
                assertTrue(E2eFx.hasText(view, "Ready"), "overall status pill reads Ready")
                assertTrue(!E2eFx.hasText(view, "Loading…"), "Loading placeholders replaced")
            }

            val requests = drainRequests(server, 4)
            assertEquals(4, requests.size, "expected 4 insight probes")
            assertTrue(requests.all { it.method == "GET" }, "all insight probes are GETs")
            val paths = requests.map { it.path.orEmpty() }
            assertTrue(paths.any { it == "/api/v1/analytics/insights" }, "insights list probed; got $paths")
            assertTrue(paths.any { it.contains("/api/v1/analytics/insights/day-score") }, "day-score probed; got $paths")
            assertTrue(paths.any { it.contains("/api/v1/analytics/insights/forecast") }, "forecast probed; got $paths")
            assertTrue(paths.any { it.contains("/api/v1/analytics/insights/report") }, "report probed; got $paths")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `all endpoints down shows honest unavailable fallback in every section`() {
        val server = MockWebServer()
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500).setBody("boom")) }
        server.start()
        try {
            val view = buildView(server)
            fireRefresh(view)

            E2eFx.waitForFx(description = "insights unavailable fallback") {
                E2eFx.hasText(view, "Временно недоступно")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Временно недоступно"), "sections show honest unavailable line")
                assertTrue(E2eFx.hasText(view, "Unavailable"), "overall status pill reads Unavailable")
                // No happy-path content leaked through.
                assertTrue(!E2eFx.hasText(view, "82/100"), "no day-score value when everything is down")
            }

            val requests = drainRequests(server, 4)
            assertEquals(4, requests.size, "the panel still probes all four endpoints")
            assertTrue(requests.all { it.method == "GET" }, "all probes are GETs")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty insights array shows no-insights message while other sections render`() {
        val server = MockWebServer()
        server.enqueue(json("[]")) // insights: available but empty
        server.enqueue(json(dayScoreObject))
        server.enqueue(json(forecastObject))
        server.enqueue(json(reportObject))
        server.start()
        try {
            val view = buildView(server)
            fireRefresh(view)

            E2eFx.waitForFx(description = "insights empty message") {
                E2eFx.hasText(view, "No insights right now.")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "No insights right now."), "empty insights array shows friendly message")
                // Other sections still rendered from their valid payloads.
                assertTrue(E2eFx.hasText(view, "82/100"), "day-score still renders alongside empty insights")
                assertTrue(E2eFx.hasText(view, "Day 7 of 31"), "forecast still renders")
                assertTrue(E2eFx.hasText(view, "Ready"), "overall status is Ready")
            }

            val requests = drainRequests(server, 4)
            assertEquals(4, requests.size)
            assertTrue(requests.all { it.method == "GET" })
        } finally {
            server.shutdown()
        }
    }
}
