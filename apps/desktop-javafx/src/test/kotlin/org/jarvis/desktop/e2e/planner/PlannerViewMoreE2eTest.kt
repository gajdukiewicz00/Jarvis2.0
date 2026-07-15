package org.jarvis.desktop.e2e.planner

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.TextField
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.planner.PlannerReadModel
import org.jarvis.desktop.features.planner.PlannerView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Additional headless-UI E2E journeys for [PlannerView] covering the
 * non-modal action + error/edge branches not exercised by
 * [PlannerViewLoadE2eTest] / [PlannerViewActionE2eTest]:
 *
 *  - complete a normal task            (fire "Complete task" -> POST /tools/todo/complete)
 *  - apply-plan-mode backend error     (POST /planner/plan/mode 500 -> Error feedback)
 *  - quick-capture create backend error(POST /tools/todo/create 500 -> Error feedback)
 *  - a degraded brief endpoint         (focus 500 -> "Временно недоступно" line)
 *  - DONE task card tone + description + tags rendering
 *
 * The modal paths (Edit, Delete + its confirm dialog) are intentionally NOT
 * fired: they call `Dialog.showAndWait()`, whose nested event loop never
 * returns headlessly.
 *
 * The recurring-occurrence Skip/Complete buttons are also NOT fired: they go
 * through [org.jarvis.desktop.features.planner.PlannerPatchClient], which the
 * view constructs pointed at the real `AppConfig` base URL (not this mock), so
 * there is no seam to observe them here — the load suite only asserts those
 * controls render.
 *
 * As in the sibling suites the widget tree lives in the ScrollPane's `content`
 * (no Scene/skin is attached headlessly), so every lookup roots at
 * `view.content`.
 */
class PlannerViewMoreE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** The six brief responses that follow the snapshot on a full route-activation fan-out. */
    private fun enqueueBriefBundle(server: MockWebServer) {
        server.enqueue(json("""{"focus":"focus"}"""))
        server.enqueue(json("""{"review":"review"}"""))
        server.enqueue(json("""{"days":{}}"""))
        server.enqueue(json("""{"focusGoal":"goal"}"""))
        server.enqueue(json("""{"mode":"NORMAL"}"""))
        server.enqueue(json("""{"mode":"NORMAL","tasks":[]}"""))
    }

    private fun drain(server: MockWebServer, count: Int): List<RecordedRequest> {
        val out = mutableListOf<RecordedRequest>()
        repeat(count) {
            val req = server.takeRequest(3, TimeUnit.SECONDS) ?: return out
            out += req
        }
        return out
    }

    private fun rootOf(view: PlannerView): Node = E2eFx.onFx { view.content }

    @Test
    fun `completing a normal task posts to todo complete and reloads the list`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.enqueue(json("""[{"id":401,"title":"Pay rent","priority":"HIGH","status":"TODO"}]"""))
        enqueueBriefBundle(server)
        // Action responses: complete then snapshot reload (task now DONE).
        server.enqueue(json("""{"id":401,"title":"Pay rent","priority":"HIGH","status":"DONE"}"""))
        server.enqueue(json("""[{"id":401,"title":"Pay rent","priority":"HIGH","status":"DONE"}]"""))
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "normal task card with complete control") {
                E2eFx.hasText(root, "Pay rent") &&
                    E2eFx.findAll<Button>(root).any { it.text == "Complete task" }
            }
            drain(server, 7)

            E2eFx.onFx {
                E2eFx.findAll<Button>(root).first { it.text == "Complete task" }.fire()
            }

            E2eFx.waitForFx(description = "completion success feedback") {
                E2eFx.hasText(root, "Planner task completed")
            }

            val actions = drain(server, 2)
            assertTrue(actions.isNotEmpty(), "complete action hit the backend")
            val complete = actions.first()
            assertEquals("POST", complete.method)
            assertTrue(complete.path!!.endsWith("/api/v1/tools/todo/complete"), "path: ${complete.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `apply plan mode surfaces an error when the backend rejects the POST`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)

            val deepWork = PlannerReadModel.PLAN_MODE_OPTIONS.first { it.code == "DEEP_WORK" }
            E2eFx.onFx {
                @Suppress("UNCHECKED_CAST")
                val combo = E2eFx.findAll<ComboBox<*>>(root)
                    .first { it.items.any { item -> item is PlannerReadModel.PlanModeOption } }
                        as ComboBox<PlannerReadModel.PlanModeOption>
                combo.value = deepWork
                E2eFx.findAll<Button>(root).first { it.text == "Apply plan mode" }.fire()
            }

            E2eFx.waitForFx(description = "error feedback after plan-mode POST fails") {
                E2eFx.hasText(root, "Error")
            }

            val req = drain(server, 1).first()
            assertEquals("POST", req.method)
            assertTrue(req.path!!.endsWith("/api/v1/planner/plan/mode"), "path: ${req.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `quick capture surfaces an error when create fails on the backend`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"nope"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)

            E2eFx.onFx {
                E2eFx.findAll<TextField>(root).first { it.promptText == "Add a task title" }.text = "Doomed task"
                E2eFx.findAll<Button>(root).first { it.text == "Create task" }.fire()
            }

            E2eFx.waitForFx(description = "error feedback after create fails") {
                E2eFx.hasText(root, "Error")
            }

            val req = drain(server, 1).first()
            assertEquals("POST", req.method)
            assertTrue(req.path!!.endsWith("/api/v1/tools/todo/create"), "path: ${req.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a degraded brief endpoint renders the temporarily-unavailable line`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        // Snapshot succeeds; the focus brief 500s -> BriefResult.Unavailable -> "Временно недоступно".
        server.enqueue(json("""[]"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("down"))   // focus
        server.enqueue(json("""{"review":"review"}"""))
        server.enqueue(json("""{"days":{}}"""))
        server.enqueue(json("""{"focusGoal":"goal"}"""))
        server.enqueue(json("""{"mode":"NORMAL"}"""))
        server.enqueue(json("""{"mode":"NORMAL","tasks":[]}"""))
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "degraded brief line rendered") {
                E2eFx.hasText(root, "Временно недоступно")
            }
            E2eFx.onFx {
                // Overall load still finished Ready — one degraded brief must not fail the whole screen.
                assertTrue(E2eFx.hasText(root, "Ready"), "screen still reaches Ready with a degraded brief")
            }

            drain(server, 7)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a DONE task with description and tags renders its tone, notes and tag line`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.enqueue(
            json(
                """[{"id":501,"title":"Archive logs","description":"rotate last month","priority":"LOW","status":"DONE","tags":["ops","cleanup"]}]"""
            )
        )
        enqueueBriefBundle(server)
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "done task card rendered") {
                E2eFx.hasText(root, "Archive logs")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "rotate last month"), "description surfaced on the card")
                assertTrue(E2eFx.hasText(root, "Tags: ops, cleanup"), "tag line rendered")
                assertTrue(E2eFx.hasText(root, "Done"), "done metric / status surfaced")
                // A DONE task exposes neither Complete nor Skip controls.
                assertTrue(
                    E2eFx.findAll<Button>(root).none { it.text == "Complete task" || it.text == "Skip occurrence" },
                    "no completion controls for a DONE task"
                )
            }

            drain(server, 7)
        } finally {
            server.shutdown()
        }
    }
}
