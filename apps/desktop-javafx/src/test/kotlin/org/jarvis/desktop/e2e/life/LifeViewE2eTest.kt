package org.jarvis.desktop.e2e.life

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ListView
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.life.LifeView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * True UI end-to-end journeys for the legacy [LifeView] (life-tracker expense
 * form + recent-expense list).
 *
 * <p>Although the shell now routes Life to LifeMapView, LifeView is still
 * on disk and fully wired, so it exercises a genuinely different journey:
 * a POST-backed "Add Expense" form plus a GET-backed recent-expenses list.
 * Each test constructs the REAL view against a [MockWebServer], drives real
 * controls (the Add Expense button / constructor auto-load), then asserts
 * BOTH the backend traffic AND the visible status label / list contents.</p>
 *
 * <p>The view hosts the LifeTab content inside a skinned [ScrollPane], so we
 * drill into its `content` node — the real widget tree the user sees.</p>
 */
@Suppress("DEPRECATION") // LifeView is deprecated but still wired; exercised here on purpose.
class LifeViewE2eTest {

    private fun body(view: Node): Node =
        requireNotNull(E2eFx.find<ScrollPane>(view)) { "LifeView should host content in a ScrollPane" }.content

    @Test
    fun `recent expenses load on open and a new expense posts then refreshes the list`() {
        val server = MockWebServer()
        // 1) constructor auto-load, 2) POST add, 3) reload after add.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""[{"amount":"12.50","currency":"€","category":"Food","description":"Lunch"}]""")
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"amount":"12.50","currency":"€","category":"Food","description":"Lunch"},
                      {"amount":"9.99","currency":"€","category":"General","description":null}
                    ]
                    """.trimIndent()
                )
        )
        server.start()
        try {
            // Constructor synchronously loads the recent expenses via GET.
            val view = E2eFx.onFx { LifeView(E2eFx.apiClientFor(server)) }

            E2eFx.waitForFx(description = "initial expenses loaded") {
                E2eFx.hasText(body(view), "Loaded 1 expenses")
            }
            E2eFx.onFx {
                val items = E2eFx.find<ListView<*>>(body(view))!!.items.map { it.toString() }
                assertTrue(items.any { it.contains("Food") }, "expense list should show the loaded Food expense: $items")
            }

            val initialGet = server.takeRequest()
            assertEquals("GET", initialGet.method)
            assertTrue(initialGet.path!!.contains("/api/v1/life/finance/expenses"), "path was ${initialGet.path}")

            // Real user gesture: fill the amount and fire the Add Expense button.
            E2eFx.onFx {
                E2eFx.findAll<TextField>(body(view)).first { it.promptText == "Amount (EUR)" }.text = "9.99"
                E2eFx.findAll<Button>(body(view)).first { it.text == "Add Expense" }.fire()
            }

            E2eFx.waitForFx(description = "expense added confirmation") {
                E2eFx.hasText(body(view), "Expense added")
            }
            E2eFx.onFx {
                val items = E2eFx.find<ListView<*>>(body(view))!!.items.map { it.toString() }
                assertTrue(items.any { it.contains("9.99") }, "reloaded list should show the new expense: $items")
            }

            val postReq = server.takeRequest()
            assertEquals("POST", postReq.method)
            assertTrue(postReq.path!!.contains("/api/v1/life/finance/expenses"), "path was ${postReq.path}")
            assertTrue(postReq.body.readUtf8().contains("9.99"), "POST body should carry the entered amount")

            val reloadGet = server.takeRequest()
            assertEquals("GET", reloadGet.method)
            assertTrue(reloadGet.path!!.contains("/api/v1/life/finance/expenses"), "path was ${reloadGet.path}")

            E2eFx.onFx { view.onRouteActivated() } // exercises the public refresh entry too
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `missing life-tracker service surfaces an honest not-deployed status`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.start()
        try {
            val view = E2eFx.onFx { LifeView(E2eFx.apiClientFor(server)) }

            E2eFx.waitForFx(description = "not-deployed status rendered") {
                E2eFx.hasText(body(view), "not deployed")
            }
            E2eFx.onFx {
                val items = E2eFx.find<ListView<*>>(body(view))!!.items.map { it.toString() }
                assertTrue(
                    items.any { it.contains("No data available") },
                    "list should show the no-data placeholder on failure: $items"
                )
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(req.path!!.contains("/api/v1/life/finance/expenses"), "path was ${req.path}")
        } finally {
            server.shutdown()
        }
    }
}
