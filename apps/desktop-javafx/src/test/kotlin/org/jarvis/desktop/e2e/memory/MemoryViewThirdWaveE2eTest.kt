package org.jarvis.desktop.e2e.memory

import javafx.event.ActionEvent
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.memory.MemoryView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * THIRD-wave UI E2E journeys for [MemoryView] — targets NON-MODAL branches left
 * uncovered by [MemoryViewE2eTest] and [MemoryViewMoreE2eTest]:
 *
 *  - [MemoryView.refreshSourceFilterOptions] RETAIN branch: a source filter
 *    selection survives a subsequent reload when that source still exists in the
 *    fresh fetch (`previousSelection in items`), while the option list is rebuilt.
 *  - the same method's RESET branch: the selection falls back to "All sources"
 *    when the previously-chosen source vanishes from the fresh fetch.
 *  - [MemoryView.onRouteActivated] NO-OP branch: once results are populated
 *    (resultsContainer size != 1) re-activating the route issues no second load.
 *  - [MemoryView.selectAllVisible] manageable-guard branch: "Select all" counts
 *    only manageable notes and skips non-manageable conversation chunks.
 *
 * All journeys avoid the Edit / Forget / Why? / Change-scope / Export / Import
 * paths, which open a modal `showAndWait()` (or a native FileChooser) that cannot
 * be driven headlessly under Monocle. [MemoryView] does NO network I/O on
 * construction, so requests are enqueued in journey order.
 */
class MemoryViewThirdWaveE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun contentOf(view: MemoryView): Node = E2eFx.onFx { view.content }

    private val memoryNote =
        """{"memoryId":"m-1","title":"Green tea preference","snippet":"prefers green tea","source":"memory","scope":"USER_PROFILE"}"""
    private val obsidianNote =
        """{"memoryId":"o-2","title":"Obsidian daily note","snippet":"today I coded","source":"obsidian","scope":"PROJECT"}"""
    private val kindleNote =
        """{"memoryId":"k-3","title":"Kindle highlight","snippet":"a saved book quote","source":"kindle","scope":"PROJECT"}"""

    private fun notesBody(vararg notes: String): String =
        """{"notes":[${notes.joinToString(",")}]}"""

    private fun fireButton(root: Node, exact: String) {
        E2eFx.onFx {
            val btn = E2eFx.findAll<Button>(root).firstOrNull { it.text == exact }
                ?: error("No button '$exact'. Present: ${E2eFx.findAll<Button>(root).map { it.text }}")
            btn.fire()
        }
    }

    /** Capture the client-side source ComboBox once (its value is mutated by these tests). */
    @Suppress("UNCHECKED_CAST")
    private fun sourceFilter(root: Node): ComboBox<String> =
        E2eFx.onFx { E2eFx.findAll<ComboBox<*>>(root).first { it.value == "All sources" } as ComboBox<String> }

    private fun selectSource(combo: ComboBox<String>, source: String) {
        E2eFx.onFx {
            combo.value = source
            combo.onAction?.handle(ActionEvent())
        }
    }

    private fun awaitRequest(server: MockWebServer, predicate: (RecordedRequest) -> Boolean): RecordedRequest {
        val deadline = System.nanoTime() + 10_000L * 1_000_000L
        while (System.nanoTime() < deadline) {
            val req = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(req)) return req
        }
        throw AssertionError("Timed out waiting for a matching backend request")
    }

    // ---------------------------------------------------------------------
    // refreshSourceFilterOptions RETAIN — a still-present source survives reload
    // ---------------------------------------------------------------------

    @Test
    fun `a source filter selection persists across a reload when that source still exists`() {
        val server = MockWebServer()
        server.enqueue(json(notesBody(memoryNote, obsidianNote)))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            fireButton(root, "Recent notes")
            E2eFx.waitForFx(description = "both source cards rendered") {
                E2eFx.hasText(root, "Green tea preference") && E2eFx.hasText(root, "Obsidian daily note")
            }
            awaitRequest(server) { it.method == "GET" }

            val combo = sourceFilter(root)
            selectSource(combo, "obsidian")
            E2eFx.waitForFx(description = "only the obsidian card remains") {
                E2eFx.hasText(root, "Obsidian daily note") && !E2eFx.hasText(root, "Green tea preference")
            }

            // Reload with a fetch that STILL contains obsidian, plus a new "kindle" source.
            server.enqueue(json(notesBody(memoryNote, obsidianNote, kindleNote)))
            fireButton(root, "Recent notes")
            awaitRequest(server) { it.method == "GET" }

            // The rebuilt option list proves the reload landed; the retained value proves the branch.
            E2eFx.waitForFx(description = "source options rebuilt to include kindle") {
                combo.items.contains("kindle")
            }
            E2eFx.onFx {
                assertEquals("obsidian", combo.value, "the still-present source stays selected across reload")
                assertTrue(E2eFx.hasText(root, "Obsidian daily note"), "obsidian card still shown post-reload")
                assertFalse(E2eFx.hasText(root, "Green tea preference"), "memory source stays filtered out")
                assertFalse(E2eFx.hasText(root, "Kindle highlight"), "the new kindle source is filtered out too")
            }

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // refreshSourceFilterOptions RESET — a vanished source falls back to All sources
    // ---------------------------------------------------------------------

    @Test
    fun `a source filter selection resets to All sources when that source disappears on reload`() {
        val server = MockWebServer()
        server.enqueue(json(notesBody(memoryNote, obsidianNote)))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            fireButton(root, "Recent notes")
            E2eFx.waitForFx(description = "both source cards rendered") {
                E2eFx.hasText(root, "Green tea preference") && E2eFx.hasText(root, "Obsidian daily note")
            }
            awaitRequest(server) { it.method == "GET" }

            val combo = sourceFilter(root)
            selectSource(combo, "obsidian")
            E2eFx.waitForFx(description = "only the obsidian card remains") {
                E2eFx.hasText(root, "Obsidian daily note") && !E2eFx.hasText(root, "Green tea preference")
            }

            // Reload with a fetch that NO LONGER contains an obsidian-sourced note.
            server.enqueue(json(notesBody(memoryNote)))
            fireButton(root, "Recent notes")
            awaitRequest(server) { it.method == "GET" }

            // obsidian drops out of the options -> the selection resets to the default.
            E2eFx.waitForFx(description = "obsidian option removed after reload") {
                !combo.items.contains("obsidian")
            }
            E2eFx.onFx {
                assertEquals("All sources", combo.value, "a vanished source resets to All sources")
                assertTrue(E2eFx.hasText(root, "Green tea preference"), "the surviving memory note renders unfiltered")
            }

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // onRouteActivated NO-OP — no reload once results are already populated
    // ---------------------------------------------------------------------

    @Test
    fun `onRouteActivated does not reload when results are already populated`() {
        val server = MockWebServer()
        server.enqueue(json(notesBody(memoryNote, obsidianNote)))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            // First activation on a fresh (placeholder-only) view loads recent notes.
            E2eFx.onFx { view.onRouteActivated() }
            E2eFx.waitForFx(description = "route activation populated the results") {
                E2eFx.hasText(root, "Green tea preference") && E2eFx.hasText(root, "Obsidian daily note")
            }
            awaitRequest(server) { it.method == "GET" }
            assertEquals(1, server.requestCount, "exactly one load so far")

            // Second activation with results already rendered (container size != 1) must be a no-op.
            E2eFx.onFx { view.onRouteActivated() }
            assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS), "no second request is issued")
            assertEquals(1, server.requestCount, "route re-activation issues no additional load")

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // selectAllVisible — counts only manageable notes, skips conversation chunks
    // ---------------------------------------------------------------------

    @Test
    fun `Select all counts only manageable notes and skips conversation chunks`() {
        val server = MockWebServer()
        // A unified-search result mixing one manageable note with one conversation chunk
        // (source == "conversation" -> not manageable, no checkbox, excluded from Select all).
        val mixedResults =
            """
            {
              "results": [
                {"memoryId":"m-1","title":"Green tea preference","snippet":"prefers green tea","source":"memory","score":0.91,"scope":"USER_PROFILE"},
                {"id":"chunk-9","title":"Chat about tea","content":"we talked about tea once","source":"conversation","similarity":0.44}
              ]
            }
            """.trimIndent()
        server.enqueue(json(mixedResults))
        server.start()
        try {
            val view = E2eFx.onFx { MemoryView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            E2eFx.onFx {
                E2eFx.findAll<javafx.scene.control.TextField>(root)
                    .first { it.promptText?.contains("Search your memory") == true }
                    .text = "tea"
            }
            fireButton(root, "Search")

            E2eFx.waitForFx(description = "both hits rendered") {
                E2eFx.hasText(root, "Green tea preference") && E2eFx.hasText(root, "Chat about tea")
            }
            awaitRequest(server) { it.method == "POST" }

            // Only the manageable note gets a checkbox; the conversation chunk does not.
            E2eFx.onFx {
                assertEquals(1, E2eFx.findAll<CheckBox>(root).size, "only the manageable note is selectable")
            }

            fireButton(root, "Select all")
            E2eFx.waitForFx(description = "only the manageable note is selected") {
                E2eFx.hasText(root, "1 selected")
            }
            E2eFx.onFx {
                assertFalse(E2eFx.hasText(root, "2 selected"), "the conversation chunk is never selected")
            }

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }
}
