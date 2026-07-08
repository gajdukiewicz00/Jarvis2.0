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
 * TRUE headless-UI E2E journeys for the Planner Control Center — the WRITE /
 * ACTION flows the user fires from real controls (apply plan mode, quick
 * capture, generate recurring occurrences) plus a client-side validation gate.
 *
 * These construct the view WITHOUT `onRouteActivated()` so no snapshot fan-out
 * runs first; the only backend traffic is the action under test (except the
 * generate-occurrences journey, which intentionally loads first so the template
 * card exists to click).
 *
 * NOTE: [PlannerView] is a [javafx.scene.control.ScrollPane]. Its widget tree
 * lives in the `content` property, and because these views are never attached
 * to a Scene the ScrollPane skin is never built — so walking the ScrollPane's
 * own `childrenUnmodifiable` finds nothing. Every scene-graph lookup here roots
 * at `view.content` (a plain Pane tree whose children ARE directly reachable).
 */
class PlannerViewActionE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

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

    /** The real widget tree root — the ScrollPane's content, not the (unskinned) ScrollPane itself. */
    private fun rootOf(view: PlannerView): Node = E2eFx.onFx { view.content }

    @Test
    fun `applying a plan mode posts the selection and refreshes the adjusted ranking`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.enqueue(json("""{"mode":"DEEP_WORK"}"""))                                   // POST plan/mode
        server.enqueue(json("""{"mode":"DEEP_WORK","energy":"HIGH","tasks":[{"title":"Deep task","priority":"HIGH"}]}""")) // GET plan/by-mode
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

            E2eFx.waitForFx(description = "adjusted ranking refreshed after apply") {
                E2eFx.hasText(root, "Mode: DEEP_WORK") && E2eFx.hasText(root, "Deep task")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "Plan mode set to Deep work"), "success feedback shown")
            }

            val requests = drain(server, 2)
            assertEquals(2, requests.size)
            val post = requests[0]
            assertEquals("POST", post.method)
            assertTrue(post.path!!.endsWith("/api/v1/planner/plan/mode"), "path: ${post.path}")
            assertTrue(post.body.readUtf8().contains("DEEP_WORK"), "mode code posted in body")
            val get = requests[1]
            assertEquals("GET", get.method)
            assertTrue(get.path!!.endsWith("/api/v1/planner/plan/by-mode"), "path: ${get.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `quick capture posts to todo create then reloads the task list`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        // 1: create response, 2: subsequent snapshot reload (todo/list)
        server.enqueue(json("""{"id":501,"title":"Draft release notes","priority":"MEDIUM","status":"TODO"}"""))
        server.enqueue(json("""[{"id":501,"title":"Draft release notes","priority":"MEDIUM","status":"TODO"}]"""))
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)

            E2eFx.onFx {
                E2eFx.findAll<TextField>(root).first { it.promptText == "Add a task title" }.text = "Draft release notes"
                E2eFx.findAll<Button>(root).first { it.text == "Create task" }.fire()
            }

            E2eFx.waitForFx(description = "created task appears in the list") {
                E2eFx.hasText(root, "Draft release notes") && E2eFx.hasText(root, "Planner task created")
            }

            val requests = drain(server, 2)
            assertEquals(2, requests.size)
            val create = requests[0]
            assertEquals("POST", create.method)
            assertTrue(create.path!!.endsWith("/api/v1/tools/todo/create"), "path: ${create.path}")
            assertTrue(create.body.readUtf8().contains("Draft release notes"), "title in create body")
            assertTrue(requests[1].path!!.endsWith("/api/v1/tools/todo/list"), "reloaded via todo/list")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `blank quick-capture title is rejected client-side with no backend call`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)

            E2eFx.onFx {
                // Leave the title field blank.
                E2eFx.findAll<Button>(root).first { it.text == "Create task" }.fire()
            }

            E2eFx.waitForFx(description = "validation warning shown") {
                E2eFx.hasText(root, "Task title is required")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "Input needed"), "warning pill shown")
            }

            // No request should have been dispatched.
            assertEquals(0, server.requestCount, "no backend call for a blank title")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `generating next occurrences for a recurring template posts to the backend`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        // Initial snapshot fan-out (7): a recurring TEMPLATE task drives the generate button.
        server.enqueue(
            json("""[{"id":300,"title":"Weekly review","priority":"MEDIUM","status":"TODO","recurrenceRule":"WEEKLY"}]""")
        )
        enqueueBriefBundle(server)
        // Action responses: generate-next-occurrences, then the snapshot reload.
        server.enqueue(json("""[{"id":301,"title":"Weekly review","priority":"MEDIUM","status":"TODO","recurrenceSourceTaskId":300}]"""))
        server.enqueue(json("""[{"id":300,"title":"Weekly review","priority":"MEDIUM","status":"TODO","recurrenceRule":"WEEKLY"}]"""))
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "recurring template card rendered") {
                E2eFx.hasText(root, "Weekly review") &&
                    E2eFx.findAll<Button>(root).any { it.text == "Generate next occurrences" }
            }
            // Drain the seven initial load requests so the queue holds only action traffic.
            drain(server, 7)

            E2eFx.onFx {
                E2eFx.findAll<Button>(root).first { it.text == "Generate next occurrences" }.fire()
            }

            E2eFx.waitForFx(description = "generate-occurrences success feedback") {
                E2eFx.hasText(root, "next occurrences")
            }

            val actionRequests = drain(server, 2)
            assertTrue(actionRequests.isNotEmpty(), "generate action hit the backend")
            val generate = actionRequests.first()
            assertEquals("POST", generate.method)
            assertTrue(
                generate.path!!.contains("/api/v1/planner/tasks/300/generate-next-occurrences"),
                "generate path: ${generate.path}"
            )
        } finally {
            server.shutdown()
        }
    }
}
