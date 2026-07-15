package org.jarvis.desktop.e2e.planner

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.DatePicker
import javafx.scene.control.TextField
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.planner.PlannerView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * THIRD-WAVE headless-UI E2E journeys for [PlannerView], targeting the
 * error/edge branches NOT already exercised by the Load / Action / More /
 * SecondWave suites:
 *
 *  - completing a normal task when the backend rejects the POST (500 → Error
 *    feedback; the happy complete is covered in the More suite)
 *  - generating next occurrences when the backend rejects the POST (500 →
 *    Error feedback; the happy generate is covered in the Action suite)
 *  - a quick-capture create carrying a due date + description, exercising the
 *    `toDueInstant()` extension and the optional description/dueDate fields, then
 *    a reload whose task card renders a formatted due date (not "No due date"),
 *    its description and its tag line
 *
 * The modal Edit/Delete paths (Dialog.showAndWait) and the PATCH-backed
 * Skip/Complete-occurrence controls (which target the real AppConfig base URL,
 * not this mock) are intentionally never fired. Every lookup roots at the
 * ScrollPane's `content`.
 */
class PlannerViewThirdWaveE2eTest {

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

    private fun rootOf(view: PlannerView): Node = E2eFx.onFx { view.content }

    @Test
    fun `completing a normal task surfaces an error when the backend rejects the POST`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.enqueue(json("""[{"id":701,"title":"Pay invoice","priority":"HIGH","status":"TODO"}]"""))
        enqueueBriefBundle(server)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "normal task card with complete control") {
                E2eFx.hasText(root, "Pay invoice") &&
                    E2eFx.findAll<Button>(root).any { it.text == "Complete task" }
            }
            drain(server, 7)

            E2eFx.onFx {
                E2eFx.findAll<Button>(root).first { it.text == "Complete task" }.fire()
            }

            E2eFx.waitForFx(description = "completion failure feedback") {
                E2eFx.hasText(root, "Error")
            }

            val req = drain(server, 1).first()
            assertEquals("POST", req.method)
            assertTrue(req.path!!.endsWith("/api/v1/tools/todo/complete"), "path: ${req.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `generating next occurrences surfaces an error when the backend rejects the POST`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.enqueue(
            json("""[{"id":800,"title":"Weekly sync","priority":"MEDIUM","status":"TODO","recurrenceRule":"WEEKLY"}]""")
        )
        enqueueBriefBundle(server)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"nope"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "recurring template card rendered") {
                E2eFx.hasText(root, "Weekly sync") &&
                    E2eFx.findAll<Button>(root).any { it.text == "Generate next occurrences" }
            }
            drain(server, 7)

            E2eFx.onFx {
                E2eFx.findAll<Button>(root).first { it.text == "Generate next occurrences" }.fire()
            }

            E2eFx.waitForFx(description = "generate failure feedback") {
                E2eFx.hasText(root, "Error")
            }

            val req = drain(server, 1).first()
            assertEquals("POST", req.method)
            assertTrue(
                req.path!!.contains("/api/v1/planner/tasks/800/generate-next-occurrences"),
                "path: ${req.path}"
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `quick capture with a due date and description creates the task and renders a dated card`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        // 1: create echo, 2: reload snapshot with the task carrying a due date, description and tags.
        server.enqueue(json("""{"id":901,"title":"File taxes","priority":"HIGH","status":"TODO"}"""))
        server.enqueue(
            json(
                """[{"id":901,"title":"File taxes","description":"gather receipts","priority":"HIGH","status":"TODO","dueDate":"2030-01-15T00:00:00Z","tags":["finance"]}]"""
            )
        )
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)

            E2eFx.onFx {
                E2eFx.findAll<TextField>(root).first { it.promptText == "Add a task title" }.text = "File taxes"
                E2eFx.findAll<TextField>(root).first { it.promptText == "Optional description" }.text = "gather receipts"
                E2eFx.find<DatePicker>(root)!!.value = LocalDate.of(2030, 1, 15)
                E2eFx.findAll<Button>(root).first { it.text == "Create task" }.fire()
            }

            E2eFx.waitForFx(description = "created task appears with its due date + description") {
                E2eFx.hasText(root, "File taxes") && E2eFx.hasText(root, "Planner task created")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "gather receipts"), "description surfaced on the reloaded card")
                assertTrue(E2eFx.hasText(root, "Tags: finance"), "tag line rendered")
                assertTrue(
                    !E2eFx.hasText(root, "No due date"),
                    "a dated task must not fall back to the No-due-date label: ${E2eFx.visibleText(root)}"
                )
            }

            val requests = drain(server, 2)
            assertEquals(2, requests.size)
            val create = requests[0]
            assertEquals("POST", create.method)
            assertTrue(create.path!!.endsWith("/api/v1/tools/todo/create"), "path: ${create.path}")
            val body = create.body.readUtf8()
            assertTrue(body.contains("File taxes"), "title in create body: $body")
            assertTrue(body.contains("gather receipts"), "description in create body: $body")
            assertTrue(body.contains("dueDate"), "due date in create body: $body")
            assertTrue(requests[1].path!!.endsWith("/api/v1/tools/todo/list"), "reloaded via todo/list")
        } finally {
            server.shutdown()
        }
    }
}
