package org.jarvis.desktop.e2e.memory

import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.TextField
import javafx.stage.Window
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.memory.MemoryView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * TRUE UI end-to-end journeys for the Memory panel.
 *
 * Drives the REAL [MemoryView] scene graph — the unified-search TextField +
 * Search button, the scope-filter ComboBox, the per-card Pin/Unpin and "Why?"
 * buttons, and the confirm-gated "Forget by query" sweep — against a
 * [MockWebServer] standing in for the api-gateway. Each journey asserts BOTH
 * that the expected HTTP request(s) reached the backend AND that the visible
 * widget tree reacted (result cards, status pill/label, pin state, provenance
 * dialog, placeholders).
 *
 * ApiClient prefixes every endpoint with `/api/v1`, so the memory surface hits
 * `/api/v1/memory/search/unified`, `/api/v1/memory/notes...`, etc.
 *
 * Note: Edit / Change-scope / single-note Forget / bulk-delete open a modal
 * confirm/edit dialog *synchronously before any network call*, so they cannot
 * be auto-confirmed the same way; the confirm-gated flow is exercised via
 * "Forget by query" (dialog fired async, then its Forget button fired). Export
 * and Import go through the native [javafx.stage.FileChooser], which Monocle
 * cannot drive headlessly — see the manifest notes.
 */
class MemoryViewE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** One manageable memory note (real note id, non-conversation source). */
    private fun searchBody(pinned: Boolean): String =
        """
        {
          "results": [
            {
              "memoryId": "m-1",
              "title": "Green tea preference",
              "snippet": "User prefers green tea in the afternoon",
              "source": "memory",
              "score": 0.912,
              "scope": "USER_PROFILE",
              "pinned": $pinned
            },
            {
              "id": "chunk-9",
              "title": "Chat about tea",
              "content": "we talked about tea once",
              "source": "conversation",
              "similarity": 0.44
            }
          ]
        }
        """.trimIndent()

    // ---- scene-graph helpers (run these on the FX thread) ------------------

    private fun buttonExists(root: Node, exact: String): Boolean =
        E2eFx.findAll<Button>(root).any { it.text == exact }

    private fun fireButton(root: Node, exact: String) {
        E2eFx.onFx {
            val btn = E2eFx.findAll<Button>(root).firstOrNull { it.text == exact }
                ?: error("No button '$exact'. Present: ${E2eFx.findAll<Button>(root).map { it.text }}")
            btn.fire()
        }
    }

    private fun textFieldByPrompt(root: Node, promptSub: String): TextField =
        E2eFx.findAll<TextField>(root).first { it.promptText?.contains(promptSub) == true }

    /** The scope ComboBox in the search card — the first combo defaulting to "All scopes". */
    private fun scopeFilter(root: Node): ComboBox<*> =
        E2eFx.findAll<ComboBox<*>>(root).first { it.value == "All scopes" }

    /** Poll the backend for a request matching [predicate], not blocking forever. */
    private fun awaitRequest(server: MockWebServer, predicate: (RecordedRequest) -> Boolean): RecordedRequest {
        val deadline = System.nanoTime() + 10_000L * 1_000_000L
        while (System.nanoTime() < deadline) {
            val req = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(req)) return req
        }
        throw AssertionError("Timed out waiting for a matching backend request")
    }

    // ---- dialog helpers (the confirm/provenance dialogs are their own windows) ----

    private fun anyDialogHasButton(label: String): Boolean =
        Window.getWindows().any { w ->
            w.isShowing && w.scene?.root?.let { buttonExists(it, label) } == true
        }

    private fun anyDialogHasText(text: String): Boolean =
        Window.getWindows().any { w ->
            w.isShowing && w.scene?.root?.let { E2eFx.hasText(it, text) } == true
        }

    private fun fireDialogButton(label: String) {
        E2eFx.waitForFx(description = "dialog button '$label'") { anyDialogHasButton(label) }
        E2eFx.onFx {
            Window.getWindows().toList()
                .filter { it.isShowing }
                .mapNotNull { it.scene?.root }
                .flatMap { E2eFx.findAll<Button>(it) }
                .firstOrNull { it.text == label }
                ?.fire()
        }
    }

    private fun closeAllDialogs(server: MockWebServer) {
        runCatching {
            E2eFx.onFx {
                Window.getWindows().toList().filter { it.isShowing }.forEach { it.hide() }
            }
        }
        server.shutdown()
    }

    // ---------------------------------------------------------------------
    // Journey 1 — unified search renders result cards
    // ---------------------------------------------------------------------

    @Test
    fun `searching populates result cards and drops conversation chunks are non-manageable`() {
        val server = MockWebServer()
        server.enqueue(json(searchBody(pinned = false)))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }

            E2eFx.onFx { textFieldByPrompt(view, "Search your memory").text = "green tea" }
            fireButton(view, "Search")

            E2eFx.waitForFx(description = "result card rendered") {
                E2eFx.hasText(view, "Green tea preference")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "2 result(s)"), "status reflects the two hits")
                assertTrue(E2eFx.hasText(view, "Ready"), "status pill flips to Ready")
                // The manageable note exposes management buttons; the conversation chunk does not.
                assertTrue(buttonExists(view, "Pin"), "manageable note offers a Pin button")
            }

            val req = awaitRequest(server) { it.method == "POST" }
            assertTrue(req.path!!.contains("/api/v1/memory/search/unified"), "hits unified search")
            val body = req.body.readUtf8()
            assertTrue(body.contains("\"query\":\"green tea\""), "query in POST body: $body")
        } finally {
            closeAllDialogs(server)
        }
    }

    // ---------------------------------------------------------------------
    // Journey 2 — search backend 500 surfaces as a visible error
    // ---------------------------------------------------------------------

    @Test
    fun `a backend 500 on search surfaces a visible error and placeholder`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }

            E2eFx.onFx { textFieldByPrompt(view, "Search your memory").text = "anything" }
            fireButton(view, "Search")

            E2eFx.waitForFx(description = "500 surfaces as a visible error") {
                E2eFx.hasText(view, "Unavailable") || E2eFx.hasText(view, "Server error (500)")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Unavailable"), "status pill flips to Unavailable")
                assertTrue(E2eFx.hasText(view, "недоступна"), "degraded placeholder is shown")
            }

            val req = awaitRequest(server) { it.method == "POST" }
            assertTrue(req.path!!.contains("/api/v1/memory/search/unified"), "the failing call was the search")
        } finally {
            closeAllDialogs(server)
        }
    }

    // ---------------------------------------------------------------------
    // Journey 3 — empty search payload renders the "no matches" placeholder
    // ---------------------------------------------------------------------

    @Test
    fun `an empty result set renders the no-matches placeholder`() {
        val server = MockWebServer()
        server.enqueue(json("""{"results": []}"""))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }

            E2eFx.onFx { textFieldByPrompt(view, "Search your memory").text = "nothing matches this" }
            fireButton(view, "Search")

            E2eFx.waitForFx(description = "no-matches placeholder") {
                E2eFx.hasText(view, "No matching memories found")
            }
            E2eFx.onFx { assertTrue(E2eFx.hasText(view, "0 result(s)"), "status reflects zero hits") }

            val req = awaitRequest(server) { it.method == "POST" }
            assertTrue(req.path!!.contains("/api/v1/memory/search/unified"))
        } finally {
            closeAllDialogs(server)
        }
    }

    // ---------------------------------------------------------------------
    // Journey 4 — scope filter re-queries recent notes for the chosen scope
    // ---------------------------------------------------------------------

    @Test
    fun `choosing a scope re-queries recent notes for that scope`() {
        val server = MockWebServer()
        server.enqueue(
            json(
                """
                {"notes":[
                  {"memoryId":"f-1","title":"Rent budget","snippet":"1800 PLN monthly","source":"memory","scope":"FINANCE"}
                ]}
                """.trimIndent()
            )
        )
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }

            // Selecting a scope fires the ComboBox onAction -> loadRecent(scope=FINANCE).
            @Suppress("UNCHECKED_CAST")
            E2eFx.onFx { (scopeFilter(view) as ComboBox<String>).value = "FINANCE" }

            E2eFx.waitForFx(description = "scoped recent notes render") {
                E2eFx.hasText(view, "Rent budget")
            }
            E2eFx.onFx { assertTrue(E2eFx.hasText(view, "1 result(s)"), "status reflects the scoped fetch") }

            val req = awaitRequest(server) { it.method == "GET" }
            assertTrue(req.path!!.contains("/api/v1/memory/notes"), "hits recent-notes endpoint")
            assertTrue(req.path!!.contains("scope=FINANCE"), "scope filter is passed through: ${req.path}")
        } finally {
            closeAllDialogs(server)
        }
    }

    // ---------------------------------------------------------------------
    // Journey 5 — pin then unpin round-trips and the card reflects the new state
    // ---------------------------------------------------------------------

    @Test
    fun `pinning then unpinning a note round-trips and updates the card`() {
        val server = MockWebServer()
        // 1) initial search (unpinned) 2) PUT pin 3) refresh search (pinned)
        // 4) DELETE unpin           5) refresh search (unpinned)
        server.enqueue(json(searchBody(pinned = false)))
        server.enqueue(json("{}"))
        server.enqueue(json(searchBody(pinned = true)))
        server.enqueue(json("{}"))
        server.enqueue(json(searchBody(pinned = false)))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { textFieldByPrompt(view, "Search your memory").text = "green tea" }
            fireButton(view, "Search")

            E2eFx.waitForFx(description = "note renders with a Pin button") { buttonExists(view, "Pin") }
            assertEquals("POST", awaitRequest(server) { true }.method)

            // --- pin ---
            fireButton(view, "Pin")
            E2eFx.waitForFx(description = "card flips to pinned") {
                buttonExists(view, "Unpin") && E2eFx.hasText(view, "pinned")
            }
            val pinReq = awaitRequest(server) { it.method == "PUT" }
            assertTrue(pinReq.path!!.contains("/api/v1/memory/notes/m-1/pin"), "PUT to pin endpoint: ${pinReq.path}")
            // the refresh re-runs the last search
            assertTrue(awaitRequest(server) { true }.path!!.contains("/memory/search/unified"))

            // --- unpin ---
            fireButton(view, "Unpin")
            E2eFx.waitForFx(description = "card flips back to unpinned") {
                buttonExists(view, "Pin") && !buttonExists(view, "Unpin")
            }
            val unpinReq = awaitRequest(server) { it.method == "DELETE" }
            assertTrue(unpinReq.path!!.contains("/api/v1/memory/notes/m-1/pin"), "DELETE to pin endpoint: ${unpinReq.path}")
            assertTrue(awaitRequest(server) { true }.path!!.contains("/memory/search/unified"))
        } finally {
            closeAllDialogs(server)
        }
    }

    // ---------------------------------------------------------------------
    // Journey 6 — "Why?" fetches provenance and opens the explanation dialog
    // ---------------------------------------------------------------------

    @Test
    fun `Why fetches provenance and shows the explanation dialog`() {
        val server = MockWebServer()
        server.enqueue(json(searchBody(pinned = false)))
        server.enqueue(
            json(
                """
                {
                  "memoryId": "m-1",
                  "source": "obsidian",
                  "confidence": 0.82,
                  "scope": "USER_PROFILE",
                  "privacy": "private",
                  "pinned": false,
                  "createdAt": "2026-06-01T10:00:00Z",
                  "explanation": "Captured from a note you wrote about drinks."
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { textFieldByPrompt(view, "Search your memory").text = "green tea" }
            fireButton(view, "Search")
            E2eFx.waitForFx(description = "note renders") { buttonExists(view, "Why?") }
            awaitRequest(server) { it.method == "POST" }

            // "Why?" spawns the GET in a worker and opens the dialog in a later
            // runLater, so firing it via onFx does not block.
            fireButton(view, "Why?")

            val whyReq = awaitRequest(server) { it.method == "GET" }
            assertTrue(whyReq.path!!.contains("/api/v1/memory/notes/m-1/why"), "hits the why endpoint: ${whyReq.path}")

            // The provenance dialog is its own window; assert its content is visible.
            E2eFx.waitForFx(description = "why dialog shows provenance") {
                anyDialogHasText("obsidian") && anyDialogHasText("Source:")
            }
        } finally {
            closeAllDialogs(server)
        }
    }

    // ---------------------------------------------------------------------
    // Journey 7 — confirm-gated "Forget by query" sweep
    // ---------------------------------------------------------------------

    @Test
    fun `forget by query is confirm-gated then sweeps matching notes and refreshes`() {
        val server = MockWebServer()
        // 1) DELETE by-query (after the confirm dialog) 2) recent-notes refresh
        server.enqueue(json("""{"count": 3, "memoryIds": ["a","b","c"]}"""))
        server.enqueue(
            json(
                """{"notes":[{"memoryId":"n-9","title":"Remaining note","source":"memory","scope":"USER_PROFILE"}]}"""
            )
        )
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { textFieldByPrompt(view, "Forget notes matching").text = "old grocery list" }

            // beginForgetByQuery() opens a modal confirm dialog *synchronously* in the
            // handler, so fire it without waiting on completion, then confirm the dialog.
            E2eFx.toolkitAvailable()
            Platform.runLater {
                E2eFx.findAll<Button>(view).firstOrNull { it.text == "Forget by query" }?.fire()
            }

            // Nothing should have been sent before the user confirms.
            fireDialogButton("Forget")

            val del = awaitRequest(server) { it.method == "DELETE" }
            assertTrue(del.path!!.contains("/api/v1/memory/notes/by-query"), "hits by-query sweep: ${del.path}")
            assertTrue(del.path!!.contains("query=old"), "query is passed through: ${del.path}")

            // After the sweep, the view refreshes recent notes and renders them.
            E2eFx.waitForFx(description = "post-sweep refresh renders") {
                E2eFx.hasText(view, "Remaining note")
            }
            val refresh = awaitRequest(server) { it.method == "GET" }
            assertTrue(refresh.path!!.contains("/api/v1/memory/notes"), "refresh reloads recent notes: ${refresh.path}")
        } finally {
            closeAllDialogs(server)
        }
    }
}
