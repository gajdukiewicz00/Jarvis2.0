package org.jarvis.desktop.e2e.analyticsinsights

import javafx.scene.Scene
import javafx.scene.control.Button
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.insights.InsightsView
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Fallback / tone-variation E2E journeys for [InsightsView] — the branches
 * [InsightsViewE2eTest] does not reach:
 *
 *  - day-score / forecast payloads that DON'T match the expected numeric shape
 *    fall back to the generic labelled key/value renderer ([renderResult]).
 *  - an insights payload that is an object (not an array) also falls back.
 *  - grade "C" -> warning tone, an "ERROR" severity insight, a high spend-pace
 *    forecast -> error-tone gauge, and a non-JSON report body -> raw block.
 *
 * As in [InsightsViewE2eTest] the view is placed in a Scene and laid out so its
 * ScrollPane content is reachable, and the refresh fan-out order is
 * insights, day-score, forecast, report.
 */
class InsightsViewFallbackE2eTest {

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
        requireNotNull(E2eFx.find<Button>(view)) { "Refresh button not found" }.fire()
    }

    private fun drainRequests(server: MockWebServer, count: Int): List<RecordedRequest> =
        (0 until count).mapNotNull { server.takeRequest(5, TimeUnit.SECONDS) }

    @Test
    fun `unexpected payload shapes fall back to the generic key-value renderer`() {
        val server = MockWebServer()
        server.enqueue(json("""{"not":"an-array"}"""))   // insights: object, not array -> fallback
        server.enqueue(json("""{"note":"no score here"}"""))  // day-score: no score/grade -> fallback
        server.enqueue(json("""{"partial":"missing fields"}"""))  // forecast: missing numbers -> fallback
        server.enqueue(json("""{"totalEvents":7,"summary":"weekly digest"}"""))  // report: generic rows
        server.start()
        try {
            val view = buildView(server)
            fireRefresh(view)

            E2eFx.waitForFx(description = "fallback rows rendered") {
                E2eFx.hasText(view, "weekly digest")
            }
            E2eFx.onFx {
                // Generic labelized rows from the fallback renderer.
                assertTrue(E2eFx.hasText(view, "no score here"), "day-score fell back to key/value rows")
                assertTrue(E2eFx.hasText(view, "missing fields"), "forecast fell back to key/value rows")
                assertTrue(E2eFx.hasText(view, "an-array"), "insights object fell back to key/value rows")
                assertTrue(E2eFx.hasText(view, "Total events"), "report labelized key rendered")
                // No score gauge/value leaked from the numeric renderer.
                assertTrue(!E2eFx.hasText(view, "/100"), "no day-score gauge for a non-matching payload")
                assertTrue(E2eFx.hasText(view, "Ready"), "all sections were available so status is Ready")
            }

            val requests = drainRequests(server, 4)
            assertTrue(requests.size == 4 && requests.all { it.method == "GET" }, "four GET probes")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `warning grade, error severity and a non-JSON report exercise the tone branches`() {
        val server = MockWebServer()
        // insights: one ERROR-severity item plus one item with no title (skipped by mapNotNull).
        server.enqueue(
            json(
                """
                [
                  {"title": "Overspend alert", "detail": "Way over budget", "severity": "ERROR"},
                  {"detail": "no title so skipped", "severity": "INFO"}
                ]
                """.trimIndent()
            )
        )
        // day-score grade C -> warning tone suffix.
        server.enqueue(json("""{"score": 61, "grade": "C", "components": {"activity": 30}}"""))
        // forecast with spend pace far ahead of month progress -> error-tone gauge + dailyRate line.
        server.enqueue(
            json(
                """{"month":"2026-07","dayOfMonth":5,"daysInMonth":30,
                    "spentSoFar":900.0,"projectedMonthEnd":1000.0,"dailyRate":180.0}"""
            )
        )
        // report: a non-JSON body -> raw block.
        server.enqueue(json("this is not json"))
        server.start()
        try {
            val view = buildView(server)
            fireRefresh(view)

            E2eFx.waitForFx(description = "grade-C day score rendered") { E2eFx.hasText(view, "61/100") }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Grade C"), "grade-C pill rendered")
                assertTrue(E2eFx.hasText(view, "Overspend alert"), "error-severity insight card rendered")
                assertTrue(E2eFx.hasText(view, "ERROR"), "error severity pill rendered")
                assertTrue(E2eFx.hasText(view, "Day 5 of 30"), "forecast rendered numeric card")
                assertTrue(E2eFx.hasText(view, "Daily rate:"), "forecast daily-rate line rendered")
                assertTrue(E2eFx.hasText(view, "this is not json"), "non-JSON report shown as a raw block")
                assertTrue(E2eFx.hasText(view, "Ready"), "overall status Ready")
            }

            val requests = drainRequests(server, 4)
            assertTrue(requests.size == 4 && requests.all { it.method == "GET" }, "four GET probes")
        } finally {
            server.shutdown()
        }
    }
}
