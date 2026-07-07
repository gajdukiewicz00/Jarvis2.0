package org.jarvis.desktop.e2e.agentswarm

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.TextArea
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.agentswarm.AgentSwarmView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * True UI end-to-end journeys for the Agent Swarm Control Center panel.
 *
 * Each test constructs the REAL [AgentSwarmView] on the FX thread, points it at
 * a [MockWebServer] standing in for the api-gateway, fires actual scene-graph
 * controls (checkboxes, text areas, buttons), then asserts BOTH that the visible
 * widget tree reacted AND that the backend received the expected HTTP request(s).
 *
 * Endpoints exercised (all under apiBaseUrl = <server>/api/v1):
 *   - POST /agents/tasks                         (submit a real, approval-gated task)
 *   - GET  /agents/roles, GET /agents/tasks      (refresh)
 *   - POST /agents/tasks/{id}/approve            (approve a pending CODER patch)
 *   - POST /agents/tasks/{id}/reject             (reject a pending CODER patch)
 *   - GET  /agents/tasks/{id}/artifacts/diff     (view/download the diff artifact)
 *   - GET  /agents/tasks/{id}/artifacts/report   (view/download the report artifact)
 */
class AgentSwarmViewE2eTest {

    // ---- scene-graph helpers (only invoked inside onFx/waitForFx) ----

    private fun button(root: Node, exactText: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == exactText }

    private fun checkBox(root: Node, exactText: String): CheckBox =
        E2eFx.findAll<CheckBox>(root).first { it.text == exactText }

    private fun goalArea(root: Node): TextArea =
        E2eFx.findAll<TextArea>(root).first { it.promptText?.contains("Swarm goal") == true }

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    // ---- Journey 1: submit a real approval-gated task -> it appears in the tasks list ----

    @Test
    fun `submitting a real task posts it and the new task card appears in the list`() {
        val server = MockWebServer()
        // submitRealTasks -> submitTask (POST /agents/tasks) then listTasks (GET /agents/tasks)
        server.enqueue(
            json("""{"taskId":"task-77","role":"CODER","goal":"Add input validation","status":"AWAITING_APPROVAL","dryRun":false}""")
        )
        server.enqueue(
            json("""[{"taskId":"task-77","role":"CODER","goal":"Add input validation","status":"AWAITING_APPROVAL","dryRun":false}]""")
        )
        server.start()
        try {
            val view = E2eFx.onFx { AgentSwarmView(E2eFx.apiClientFor(server)) }

            // The user types a goal, selects the CODER role, flips on "require approval",
            // then presses the (now relabelled) submit button.
            E2eFx.onFx {
                goalArea(view).text = "Add input validation"
                checkBox(view, "CODER").isSelected = true
                checkBox(view, "Real run — require approval (no dry-run)").isSelected = true
            }
            // The toggle relabels the start button from "Start dry-run swarm" to "Submit real task(s)".
            E2eFx.waitForFx(description = "start button relabels for real run") {
                E2eFx.findAll<Button>(view).any { it.text == "Submit real task(s)" }
            }
            E2eFx.onFx { button(view, "Submit real task(s)").fire() }

            // The task card renders with role, id, and the AWAITING_APPROVAL status pill.
            E2eFx.waitForFx(description = "new task card appears") {
                E2eFx.hasText(view, "task-77") && E2eFx.hasText(view, "AWAITING_APPROVAL")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "CODER"), "role visible on card")
                assertTrue(E2eFx.hasText(view, "Submitted 1 real task(s)"), "submit result summary shown")
                // The awaiting-approval card exposes Approve/Reject controls.
                assertTrue(E2eFx.findAll<Button>(view).any { it.text == "Approve" }, "Approve button present")
                assertTrue(E2eFx.findAll<Button>(view).any { it.text == "Reject" }, "Reject button present")
            }

            // Backend assertions: first the real (non-dry-run, approval-gated) submission...
            val submit = server.takeRequest()
            assertEquals("POST", submit.method)
            assertEquals("/api/v1/agents/tasks", submit.path)
            val submitBody = submit.body.readUtf8()
            assertTrue(submitBody.contains("\"role\":\"CODER\""), "role in payload: $submitBody")
            assertTrue(submitBody.contains("\"dryRun\":false"), "dryRun=false in payload: $submitBody")
            assertTrue(submitBody.contains("\"approvalRequired\":true"), "approvalRequired=true in payload: $submitBody")
            // ...then the follow-up task-list reload that repopulated the panel.
            val list = server.takeRequest()
            assertEquals("GET", list.method)
            assertEquals("/api/v1/agents/tasks", list.path)
        } finally {
            server.shutdown()
        }
    }

    // ---- Journey 2: approve a proposed patch (AWAITING_APPROVAL -> COMPLETED) ----

    @Test
    fun `approving a pending patch posts approve and the card status flips to completed`() {
        val server = MockWebServer()
        // onRouteActivated -> refresh: GET /agents/roles then GET /agents/tasks
        server.enqueue(json("""[{"role":"CODER","description":"Writes code","sandboxRequired":true}]"""))
        server.enqueue(
            json("""[{"taskId":"task-9","role":"CODER","goal":"Patch login","status":"AWAITING_APPROVAL","dryRun":false}]""")
        )
        // approveTask -> POST /agents/tasks/task-9/approve then listTasks GET
        server.enqueue(json("""{"taskId":"task-9","role":"CODER","goal":"Patch login","status":"COMPLETED","dryRun":false}"""))
        server.enqueue(json("""[{"taskId":"task-9","role":"CODER","goal":"Patch login","status":"COMPLETED","dryRun":false}]"""))
        server.start()
        try {
            val view = E2eFx.onFx { AgentSwarmView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "awaiting-approval card loads") {
                E2eFx.findAll<Button>(view).any { it.text == "Approve" }
            }
            // Drain the two refresh requests before firing so the next takeRequest is the approve.
            server.takeRequest() // GET /agents/roles
            server.takeRequest() // GET /agents/tasks

            E2eFx.onFx { button(view, "Approve").fire() }

            E2eFx.waitForFx(description = "status flips to COMPLETED") {
                E2eFx.hasText(view, "COMPLETED") && E2eFx.hasText(view, "patch applied")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Approved task task-9"), "approval status text shown")
                // Once completed it is terminal and no longer awaiting -> Approve button gone.
                assertFalse(E2eFx.findAll<Button>(view).any { it.text == "Approve" }, "Approve button removed after completion")
            }

            val approve = server.takeRequest()
            assertEquals("POST", approve.method)
            assertEquals("/api/v1/agents/tasks/task-9/approve", approve.path)
            val reload = server.takeRequest()
            assertEquals("GET", reload.method)
            assertEquals("/api/v1/agents/tasks", reload.path)
        } finally {
            server.shutdown()
        }
    }

    // ---- Journey 3: reject a proposed patch (nothing applied) ----

    @Test
    fun `rejecting a pending patch posts reject and reports nothing was applied`() {
        val server = MockWebServer()
        server.enqueue(json("""[{"role":"CODER","description":"Writes code","sandboxRequired":true}]"""))
        server.enqueue(
            json("""[{"taskId":"task-3","role":"CODER","goal":"Risky change","status":"AWAITING_APPROVAL","dryRun":false}]""")
        )
        // rejectTask -> POST /agents/tasks/task-3/reject then listTasks GET
        server.enqueue(json("""{"taskId":"task-3","role":"CODER","goal":"Risky change","status":"CANCELLED","dryRun":false}"""))
        server.enqueue(json("""[{"taskId":"task-3","role":"CODER","goal":"Risky change","status":"CANCELLED","dryRun":false}]"""))
        server.start()
        try {
            val view = E2eFx.onFx { AgentSwarmView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "awaiting-approval card loads") {
                E2eFx.findAll<Button>(view).any { it.text == "Reject" }
            }
            server.takeRequest() // GET /agents/roles
            server.takeRequest() // GET /agents/tasks

            E2eFx.onFx { button(view, "Reject").fire() }

            E2eFx.waitForFx(description = "reject confirmation appears") {
                E2eFx.hasText(view, "nothing was applied") && E2eFx.hasText(view, "CANCELLED")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Rejected task task-3"), "reject status text shown")
            }

            val reject = server.takeRequest()
            assertEquals("POST", reject.method)
            assertEquals("/api/v1/agents/tasks/task-3/reject", reject.path)
        } finally {
            server.shutdown()
        }
    }

    // ---- Journey 4: view the diff artifact inline ----

    @Test
    fun `viewing the diff artifact fetches it and shows the patch in the card`() {
        val server = MockWebServer()
        server.enqueue(json("""[{"role":"CODER","description":"Writes code","sandboxRequired":true}]"""))
        server.enqueue(
            json("""[{"taskId":"task-42","role":"CODER","goal":"Add tests","status":"COMPLETED","dryRun":false}]""")
        )
        val diff = "--- a/login.kt\n+++ b/login.kt\n@@ -1 +1 @@\n-old\n+new"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(diff))
        server.start()
        try {
            val view = E2eFx.onFx { AgentSwarmView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "completed task card loads") {
                E2eFx.findAll<Button>(view).any { it.text == "View diff" }
            }
            server.takeRequest() // GET /agents/roles
            server.takeRequest() // GET /agents/tasks

            E2eFx.onFx { button(view, "View diff").fire() }

            E2eFx.waitForFx(description = "diff content becomes visible") {
                E2eFx.findAll<TextArea>(view).any { it.isVisible && it.text?.contains("+++ b/login.kt") == true }
            }

            val artifact = server.takeRequest()
            assertEquals("GET", artifact.method)
            assertEquals("/api/v1/agents/tasks/task-42/artifacts/diff", artifact.path)
        } finally {
            server.shutdown()
        }
    }

    // ---- Journey 5: view the report artifact inline ----

    @Test
    fun `viewing the report artifact fetches it and shows the rendered report`() {
        val server = MockWebServer()
        server.enqueue(json("""[{"role":"CODER","description":"Writes code","sandboxRequired":true}]"""))
        server.enqueue(
            json("""[{"taskId":"task-88","role":"TESTER","goal":"Cover edge cases","status":"COMPLETED","dryRun":false}]""")
        )
        val report = "# Task Report\nSummary: all green\nRisks: none"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/markdown").setBody(report))
        server.start()
        try {
            val view = E2eFx.onFx { AgentSwarmView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "completed task card loads") {
                E2eFx.findAll<Button>(view).any { it.text == "View report" }
            }
            server.takeRequest() // GET /agents/roles
            server.takeRequest() // GET /agents/tasks

            E2eFx.onFx { button(view, "View report").fire() }

            E2eFx.waitForFx(description = "report content becomes visible") {
                E2eFx.findAll<TextArea>(view).any { it.isVisible && it.text?.contains("all green") == true }
            }

            val artifact = server.takeRequest()
            assertEquals("GET", artifact.method)
            assertEquals("/api/v1/agents/tasks/task-88/artifacts/report", artifact.path)
        } finally {
            server.shutdown()
        }
    }

    // ---- Edge: refresh against a backend 500 surfaces an "Unavailable" status ----

    @Test
    fun `refresh against a backend error surfaces an unavailable status pill`() {
        val server = MockWebServer()
        // refresh -> listRoles GET first; a 500 aborts the whole block and is caught by runBusy.
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.start()
        try {
            val view = E2eFx.onFx { AgentSwarmView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "error surfaces in the panel") {
                E2eFx.hasText(view, "Unavailable") && E2eFx.hasText(view, "Server error")
            }
            E2eFx.onFx {
                // The role/task placeholders stay put because nothing loaded.
                assertTrue(E2eFx.hasText(view, "Refresh to load the role catalog."), "roles placeholder retained")
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertEquals("/api/v1/agents/roles", req.path)
        } finally {
            server.shutdown()
        }
    }

    // ---- Edge: refresh with empty payloads renders the empty-state placeholders ----

    @Test
    fun `refresh with empty roles and tasks renders empty-state placeholders`() {
        val server = MockWebServer()
        server.enqueue(json("""[]"""))
        server.enqueue(json("""[]"""))
        server.start()
        try {
            val view = E2eFx.onFx { AgentSwarmView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "empty-state placeholders render") {
                E2eFx.hasText(view, "No roles returned by the agent service.") &&
                    E2eFx.hasText(view, "No agent tasks yet.")
            }
            E2eFx.onFx {
                assertFalse(E2eFx.findAll<Button>(view).any { it.text == "View diff" }, "no task cards -> no artifact buttons")
            }

            assertEquals("/api/v1/agents/roles", server.takeRequest().path)
            assertEquals("/api/v1/agents/tasks", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    // ---- Edge: client-side validation blocks a submit with no goal and makes no request ----

    @Test
    fun `submitting with a blank goal shows a validation message and issues no request`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { AgentSwarmView(E2eFx.apiClientFor(server)) }
            // Select a role but leave the goal empty, then press the default (dry-run) button.
            E2eFx.onFx {
                checkBox(view, "CODER").isSelected = true
                button(view, "Start dry-run swarm").fire()
            }

            E2eFx.waitForFx(description = "goal validation message shows") {
                E2eFx.hasText(view, "Enter a goal first.")
            }
            // No network activity should have occurred (validation returns before runBusy).
            assertEquals(0, server.requestCount)
            assertNotNull(view)
        } finally {
            server.shutdown()
        }
    }
}
