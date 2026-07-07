package org.jarvis.desktop.e2e.planner

import javafx.scene.control.Button
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.planner.PlannerView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * TRUE headless-UI E2E journeys for the Planner Control Center screen — the
 * READ/LOAD flows. Each test constructs the REAL [PlannerView], points it at a
 * [MockWebServer] standing in for the api-gateway, triggers the load exactly as
 * the shell does (`onRouteActivated()`), then asserts BOTH that the visible
 * scene graph reacted AND that the backend received the expected requests.
 *
 * On `onRouteActivated()` the view fans out seven sequential backend calls on
 * its worker thread, in this fixed order:
 *   1. POST /api/v1/tools/todo/list        (task snapshot)
 *   2. GET  /api/v1/planner/focus
 *   3. GET  /api/v1/planner/evening-review
 *   4. GET  /api/v1/planner/weekly
 *   5. GET  /api/v1/planner/daily?date=<tomorrow>
 *   6. GET  /api/v1/planner/plan/mode
 *   7. GET  /api/v1/planner/plan/by-mode   (energy-adjusted ranking)
 */
class PlannerViewLoadE2eTest {

    /** Enqueue the six brief responses that follow the snapshot on a full refresh. */
    private fun enqueueBriefBundle(server: MockWebServer) {
        // focus
        server.enqueue(json("""{"focus":"Ship the planner E2E suite"}"""))
        // evening-review
        server.enqueue(json("""{"review":"Reviewed the day's planner tasks"}"""))
        // weekly
        server.enqueue(json("""{"days":{"monday":["Write quarterly report"],"tuesday":["Buy groceries"]}}"""))
        // daily (tomorrow)
        server.enqueue(json("""{"focusGoal":"Prep tomorrow's demo","tasksForDay":[{"title":"Standup"}]}"""))
        // plan/mode
        server.enqueue(json("""{"mode":"DEEP_WORK"}"""))
        // plan/by-mode (adjusted plan)
        server.enqueue(json("""{"mode":"DEEP_WORK","energy":"HIGH","tasks":[{"title":"Write quarterly report","priority":"HIGH"}]}"""))
    }

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun drain(server: MockWebServer, count: Int): List<RecordedRequest> {
        val out = mutableListOf<RecordedRequest>()
        repeat(count) {
            val req = server.takeRequest(3, TimeUnit.SECONDS) ?: return out
            out += req
        }
        return out
    }

    @Test
    fun `route activation loads weekly, tomorrow and adjusted plan, and renders tasks`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        // 1: snapshot list
        server.enqueue(
            json(
                """
                [
                  {"id":101,"title":"Write quarterly report","description":"Q3 numbers","priority":"HIGH","status":"TODO","tags":["work"]},
                  {"id":102,"title":"Buy groceries","priority":"LOW","status":"DONE"}
                ]
                """.trimIndent()
            )
        )
        enqueueBriefBundle(server)
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            // The final Platform.runLater sets the adjusted-plan label last, so waiting
            // on its text proves the whole render pass completed.
            E2eFx.waitForFx(description = "planner snapshot + briefs rendered") {
                E2eFx.hasText(view, "Write quarterly report") &&
                    E2eFx.hasText(view, "Mode: DEEP_WORK")
            }

            // Visible scene graph reacted across every section.
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Buy groceries"), "task list rendered both tasks")
                assertTrue(E2eFx.hasText(view, "Ship the planner E2E suite"), "today's focus rendered")
                assertTrue(E2eFx.hasText(view, "Monday"), "weekly plan rendered day breakdown")
                assertTrue(E2eFx.hasText(view, "Prep tomorrow's demo"), "tomorrow's plan rendered")
                assertTrue(E2eFx.hasText(view, "Energy: HIGH"), "adjusted plan-by-mode rendered")
                assertTrue(E2eFx.hasText(view, "Ready"), "feedback pill flipped to Ready")
            }

            // Backend received the expected fan-out in order.
            val requests = drain(server, 7)
            assertEquals(7, requests.size, "all seven planner endpoints were called")
            val first = requests.first()
            assertEquals("POST", first.method)
            assertTrue(first.path!!.endsWith("/api/v1/tools/todo/list"), "snapshot loads via todo/list: ${first.path}")
            val paths = requests.map { it.path!! }
            assertTrue(paths.any { it.endsWith("/api/v1/planner/weekly") }, "weekly requested: $paths")
            assertTrue(paths.any { it.startsWith("/api/v1/planner/daily?date=") }, "tomorrow daily requested: $paths")
            assertTrue(paths.any { it.endsWith("/api/v1/planner/plan/by-mode") }, "adjusted plan requested: $paths")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty snapshot renders the no-tasks placeholder`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.enqueue(json("[]"))
        enqueueBriefBundle(server)
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "empty-state placeholder shown") {
                E2eFx.hasText(view, "No planner tasks yet")
            }

            val first = drain(server, 1).first()
            assertEquals("POST", first.method)
            assertTrue(first.path!!.endsWith("/api/v1/tools/todo/list"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `snapshot HTTP 500 surfaces an error state without crashing`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        // The snapshot POST is the first call; a 500 there aborts the whole refresh
        // block, so no further endpoints are hit.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "error state rendered") {
                E2eFx.hasText(view, "Unable to load planner tasks") &&
                    E2eFx.hasText(view, "Planner load failed")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Error"), "feedback pill flipped to Error")
            }

            val first = drain(server, 1).first()
            assertEquals("POST", first.method)
            assertTrue(first.path!!.endsWith("/api/v1/tools/todo/list"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `recurring occurrence task exposes skip and complete-occurrence controls`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        // recurrenceSourceTaskId marks this as a single generated occurrence.
        server.enqueue(
            json(
                """[{"id":201,"title":"Daily standup","priority":"MEDIUM","status":"TODO","recurrenceSourceTaskId":200}]"""
            )
        )
        enqueueBriefBundle(server)
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "recurring occurrence card rendered") {
                E2eFx.hasText(view, "Daily standup")
            }

            E2eFx.onFx {
                val buttonLabels = E2eFx.findAll<Button>(view).mapNotNull { it.text }
                assertTrue(buttonLabels.contains("Skip occurrence"), "skip control exposed: $buttonLabels")
                assertTrue(buttonLabels.contains("Complete occurrence"), "complete-occurrence control exposed: $buttonLabels")
                assertTrue(E2eFx.hasText(view, "Recurring"), "recurring pill rendered")
            }
        } finally {
            server.shutdown()
        }
    }
}
