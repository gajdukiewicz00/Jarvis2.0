package org.jarvis.desktop.e2e.memory

import javafx.event.ActionEvent
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.TextField
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.memory.MemoryView
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * SECOND-wave UI E2E journeys for [MemoryView] — covers the NON-MODAL branches
 * left uncovered by [MemoryViewE2eTest]:
 *
 *  - blank-query guard on Search (the early-return "Input needed" branch)
 *  - the "Recent notes" button -> GET /memory/notes (loadRecent, no scope)
 *  - the client-side source filter narrowing rendered cards (renderFilteredResults)
 *  - the bulk-select controls: "Select all" / "Clear selection" toggling the
 *    selection summary + enabling/disabling "Delete selected" (WITHOUT firing it)
 *  - a per-card CheckBox toggle updating the selection summary
 *  - onRouteActivated() lazily loading recent notes when only the placeholder shows
 *
 * All of these are additive on top of search/pin/scope journeys and deliberately
 * avoid the Edit / Forget / Why? / Change-scope / Export / Import paths, which
 * open a modal showAndWait() (or a native FileChooser) that cannot be driven
 * headlessly under Monocle.
 */
class MemoryViewMoreE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun contentOf(view: MemoryView): Node = E2eFx.onFx { view.content }

    /** Two manageable notes from two distinct sources — feeds the source-filter branch. */
    private val twoSourceNotes =
        """
        {
          "notes": [
            {"memoryId":"m-1","title":"Green tea preference","snippet":"prefers green tea","source":"memory","scope":"USER_PROFILE"},
            {"memoryId":"o-2","title":"Obsidian daily note","snippet":"today I coded","source":"obsidian","scope":"PROJECT"}
          ]
        }
        """.trimIndent()

    private fun buttonExists(root: Node, exact: String): Boolean =
        E2eFx.findAll<Button>(root).any { it.text == exact }

    private fun fireButton(root: Node, exact: String) {
        E2eFx.onFx {
            val btn = E2eFx.findAll<Button>(root).firstOrNull { it.text == exact }
                ?: error("No button '$exact'. Present: ${E2eFx.findAll<Button>(root).map { it.text }}")
            btn.fire()
        }
    }

    private fun deleteSelectedDisabled(root: Node): Boolean =
        E2eFx.onFx { E2eFx.findAll<Button>(root).first { it.text == "Delete selected" }.isDisable }

    private fun textFieldByPrompt(root: Node, promptSub: String): TextField =
        E2eFx.findAll<TextField>(root).first { it.promptText?.contains(promptSub) == true }

    /** The client-side source ComboBox — the only combo defaulting to "All sources". */
    @Suppress("UNCHECKED_CAST")
    private fun sourceFilter(root: Node): ComboBox<String> =
        E2eFx.findAll<ComboBox<*>>(root).first { it.value == "All sources" } as ComboBox<String>

    private fun awaitRequest(server: MockWebServer, predicate: (RecordedRequest) -> Boolean): RecordedRequest {
        val deadline = System.nanoTime() + 10_000L * 1_000_000L
        while (System.nanoTime() < deadline) {
            val req = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(req)) return req
        }
        throw AssertionError("Timed out waiting for a matching backend request")
    }

    // ---------------------------------------------------------------------
    // Blank query — Search short-circuits to an "Input needed" warning, no call
    // ---------------------------------------------------------------------

    @Test
    fun `searching with a blank query shows an input-needed warning and issues no request`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            // Whitespace-only query trims to blank -> the early-return branch fires.
            E2eFx.onFx { textFieldByPrompt(root, "Search your memory").text = "   " }
            fireButton(root, "Search")

            E2eFx.waitForFx(description = "input-needed warning shown") {
                E2eFx.hasText(root, "Enter a search query first")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "Input needed"), "status pill flips to Input needed")
            }
            // No backend request should have been made.
            assertTrue(server.requestCount == 0, "blank query must not hit the backend")

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // "Recent notes" button -> loadRecent() GET /memory/notes (no scope param)
    // ---------------------------------------------------------------------

    @Test
    fun `pressing Recent notes loads the notes feed with no scope filter`() {
        val server = MockWebServer()
        server.enqueue(json(twoSourceNotes))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            fireButton(root, "Recent notes")

            E2eFx.waitForFx(description = "recent notes rendered") {
                E2eFx.hasText(root, "Green tea preference") && E2eFx.hasText(root, "Obsidian daily note")
            }
            E2eFx.onFx { assertTrue(E2eFx.hasText(root, "2 result(s)"), "status reflects two notes") }

            val req = awaitRequest(server) { it.method == "GET" }
            assertTrue(req.path!!.contains("/api/v1/memory/notes"), "hits recent-notes endpoint: ${req.path}")
            assertFalse(req.path!!.contains("scope="), "All-scopes default sends no scope param: ${req.path}")

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // Source filter narrows the rendered cards client-side (no re-query)
    // ---------------------------------------------------------------------

    @Test
    fun `selecting a source filters the rendered cards without a second request`() {
        val server = MockWebServer()
        server.enqueue(json(twoSourceNotes))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            fireButton(root, "Recent notes")
            E2eFx.waitForFx(description = "both source cards rendered") {
                E2eFx.hasText(root, "Green tea preference") && E2eFx.hasText(root, "Obsidian daily note")
            }
            awaitRequest(server) { it.method == "GET" }

            // Setting ComboBox.value does not fire onAction; dispatch the wired handler.
            E2eFx.onFx {
                val combo = sourceFilter(root)
                assertTrue(combo.items.contains("obsidian"), "source options repopulated from the fetch")
                combo.value = "obsidian"
                combo.onAction?.handle(ActionEvent())
            }

            E2eFx.waitForFx(description = "only the obsidian card remains") {
                E2eFx.hasText(root, "Obsidian daily note") && !E2eFx.hasText(root, "Green tea preference")
            }
            // Client-side filter must not have issued another backend call.
            assertTrue(server.requestCount == 1, "source filter is client-side only, still one request")

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // Bulk select: Select all -> summary + enabled Delete; Clear -> disabled
    // ---------------------------------------------------------------------

    @Test
    fun `Select all then Clear selection toggles the summary and Delete selected state`() {
        val server = MockWebServer()
        server.enqueue(json(twoSourceNotes))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            fireButton(root, "Recent notes")
            E2eFx.waitForFx(description = "notes rendered") { E2eFx.hasText(root, "Obsidian daily note") }
            awaitRequest(server) { it.method == "GET" }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "0 selected"), "starts with nothing selected")
                assertTrue(deleteSelectedDisabled(root), "Delete selected starts disabled")
            }

            fireButton(root, "Select all")
            E2eFx.waitForFx(description = "both notes selected") { E2eFx.hasText(root, "2 selected") }
            assertFalse(deleteSelectedDisabled(root), "Delete selected enabled once a note is selected")

            fireButton(root, "Clear selection")
            E2eFx.waitForFx(description = "selection cleared") { E2eFx.hasText(root, "0 selected") }
            assertTrue(deleteSelectedDisabled(root), "Delete selected re-disabled after clearing")

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // Per-card checkbox toggle updates the selection summary both ways
    // ---------------------------------------------------------------------

    @Test
    fun `toggling a per-card checkbox updates the selection summary`() {
        val server = MockWebServer()
        server.enqueue(json(twoSourceNotes))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            fireButton(root, "Recent notes")
            E2eFx.waitForFx(description = "notes rendered") { E2eFx.hasText(root, "Obsidian daily note") }
            awaitRequest(server) { it.method == "GET" }

            // CheckBox.fire() flips isSelected then dispatches the handler, so the first
            // fire selects (add branch) and the second deselects (remove branch).
            E2eFx.onFx { E2eFx.findAll<CheckBox>(root).first().fire() }
            E2eFx.waitForFx(description = "one selected") { E2eFx.hasText(root, "1 selected") }
            assertFalse(deleteSelectedDisabled(root), "Delete selected enabled with one checked")

            E2eFx.onFx { E2eFx.findAll<CheckBox>(root).first().fire() }
            E2eFx.waitForFx(description = "back to zero selected") { E2eFx.hasText(root, "0 selected") }
            assertTrue(deleteSelectedDisabled(root), "Delete selected disabled again")

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // onRouteActivated lazily loads recent notes when only the placeholder shows
    // ---------------------------------------------------------------------

    @Test
    fun `onRouteActivated loads recent notes when the view is still on its placeholder`() {
        val server = MockWebServer()
        server.enqueue(json(twoSourceNotes))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            // Fresh view shows only the placeholder card (resultsContainer size == 1) ->
            // activating the route triggers the lazy recent-notes load.
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "route activation loaded notes") {
                E2eFx.hasText(root, "Green tea preference")
            }
            val req = awaitRequest(server) { it.method == "GET" }
            assertTrue(req.path!!.contains("/api/v1/memory/notes"), "route activation hit recent notes: ${req.path}")
            assertFalse(buttonExists(root, "nonexistent"), "sanity: button lookup works on the content tree")

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }
}
