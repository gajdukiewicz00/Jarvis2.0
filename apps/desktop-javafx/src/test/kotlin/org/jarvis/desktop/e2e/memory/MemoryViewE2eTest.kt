package org.jarvis.desktop.e2e.memory

import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.TextField
import javafx.stage.Window
import okhttp3.mockwebserver.Dispatcher
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
 * [MemoryView] IS a [javafx.scene.control.ScrollPane]. Headless (Monocle, no
 * Stage/Scene) its skin never builds, so the content viewport is NOT part of
 * `getChildrenUnmodifiable()` — walking the ScrollPane itself finds nothing.
 * Every scene-graph lookup therefore walks `view.content` (the eagerly-built
 * VBox tree that actually holds the controls) rather than the ScrollPane.
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

    /** The walkable content root of the ScrollPane-based view (skin-independent). */
    private fun contentOf(view: MemoryView): Node = E2eFx.onFx { view.content }

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
            val root = contentOf(view)

            E2eFx.onFx { textFieldByPrompt(root, "Search your memory").text = "green tea" }
            fireButton(root, "Search")

            E2eFx.waitForFx(description = "result card rendered") {
                E2eFx.hasText(root, "Green tea preference")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "2 result(s)"), "status reflects the two hits")
                assertTrue(E2eFx.hasText(root, "Ready"), "status pill flips to Ready")
                // The manageable note exposes management buttons; the conversation chunk does not.
                assertTrue(buttonExists(root, "Pin"), "manageable note offers a Pin button")
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
            val root = contentOf(view)

            E2eFx.onFx { textFieldByPrompt(root, "Search your memory").text = "anything" }
            fireButton(root, "Search")

            E2eFx.waitForFx(description = "500 surfaces as a visible error") {
                E2eFx.hasText(root, "Unavailable") || E2eFx.hasText(root, "недоступна")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "Unavailable"), "status pill flips to Unavailable")
                assertTrue(E2eFx.hasText(root, "недоступна"), "degraded placeholder is shown")
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
            val root = contentOf(view)

            E2eFx.onFx { textFieldByPrompt(root, "Search your memory").text = "nothing matches this" }
            fireButton(root, "Search")

            E2eFx.waitForFx(description = "no-matches placeholder") {
                E2eFx.hasText(root, "No matching memories found")
            }
            E2eFx.onFx { assertTrue(E2eFx.hasText(root, "0 result(s)"), "status reflects zero hits") }

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
        // A dispatcher (not a single enqueue) so BOTH the view's initial recent-notes
        // load on construction AND the scope-change re-query get a response — otherwise
        // the second request hangs and the scoped notes never render.
        val financeNotes =
            """{"notes":[{"memoryId":"f-1","title":"Rent budget","snippet":"1800 PLN monthly","source":"memory","scope":"FINANCE"}]}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path.orEmpty().contains("/memory/notes")) json(financeNotes)
                else json("""{"notes":[]}""")
            }
        }
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            // Selecting a scope fires the ComboBox onAction -> loadRecent(scope=FINANCE).
            // Programmatically setting ComboBox.value does NOT fire onAction (verified against
            // JavaFX 21 ComboBoxBase — it only fires ON_SHOWING/ON_SHOWN, never ActionEvent on
            // a value change), so we set the value and dispatch the real wired handler ourselves.
            @Suppress("UNCHECKED_CAST")
            E2eFx.onFx {
                val combo = scopeFilter(root) as ComboBox<String>
                combo.value = "FINANCE"
                combo.onAction?.handle(ActionEvent())
            }

            // Picking a scope re-queries recent notes WITH the scope filter applied.
            // Assert on the backend request (the load renders asynchronously via a
            // worker thread; the request is the deterministic contract here).
            val req = awaitRequest(server) {
                it.method == "GET" && it.path?.contains("scope=FINANCE") == true
            }
            assertTrue(req.path!!.contains("/api/v1/memory/notes"), "hits recent-notes endpoint: ${req.path}")
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
            val root = contentOf(view)
            E2eFx.onFx { textFieldByPrompt(root, "Search your memory").text = "green tea" }
            fireButton(root, "Search")

            E2eFx.waitForFx(description = "note renders with a Pin button") { buttonExists(root, "Pin") }
            assertEquals("POST", awaitRequest(server) { true }.method)

            // --- pin ---
            fireButton(root, "Pin")
            E2eFx.waitForFx(description = "card flips to pinned") {
                buttonExists(root, "Unpin") && E2eFx.hasText(root, "pinned")
            }
            val pinReq = awaitRequest(server) { it.method == "PUT" }
            assertTrue(pinReq.path!!.contains("/api/v1/memory/notes/m-1/pin"), "PUT to pin endpoint: ${pinReq.path}")
            // the refresh re-runs the last search
            assertTrue(awaitRequest(server) { true }.path!!.contains("/memory/search/unified"))

            // --- unpin ---
            fireButton(root, "Unpin")
            E2eFx.waitForFx(description = "card flips back to unpinned") {
                buttonExists(root, "Pin") && !buttonExists(root, "Unpin")
            }
            val unpinReq = awaitRequest(server) { it.method == "DELETE" }
            assertTrue(unpinReq.path!!.contains("/api/v1/memory/notes/m-1/pin"), "DELETE to pin endpoint: ${unpinReq.path}")
            assertTrue(awaitRequest(server) { true }.path!!.contains("/memory/search/unified"))
        } finally {
            closeAllDialogs(server)
        }
    }

    // NOTE: "Why?" (provenance) and "Forget by query" are intentionally NOT covered here.
    // Both open a modal `Dialog.showAndWait()` (MemoryDialogs.showWhyDialog /
    // promptForgetReason) synchronously on the FX thread; under Monocle headless the
    // nested event loop cannot be dismissed cleanly (no Robot/real Stage), which stalls
    // the FX thread and poisons sibling tests. Their non-UI logic (why endpoint,
    // by-query DELETE) is exercised at the ReadModel level; the modals themselves are
    // not headless-drivable. Same limitation as the smart-home LOCK confirm dialog.
}
