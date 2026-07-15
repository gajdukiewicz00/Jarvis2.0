package org.jarvis.desktop.e2e.tabs

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.ui.tabs.LifeTab
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Headless UI journeys for [LifeTab]. Unlike the analytics tab, LifeTab loads
 * its recent-expense list SYNCHRONOUSLY on the FX thread during construction,
 * so a [Dispatcher] must be armed before the tab is built. The add-expense,
 * start/stop-timer and refresh controls all POST/GET directly with no modal
 * dialog, so they are safe to fire headlessly.
 */
class LifeTabE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val expensesJson =
        """[{"amount":"12.50","currency":"€","category":"Food","description":"Lunch"},{"amount":"9.99","currency":"€","category":"General"}]"""

    private fun button(root: Node, label: String): Button =
        requireNotNull(E2eFx.findAll<Button>(root).firstOrNull { it.text == label }) { "button '$label' not found" }

    @Test
    fun `recent expenses load on open then a new expense posts to the backend`() {
        val server = MockWebServer()
        val calls = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls.add("${request.method} ${request.path}")
                return when {
                    request.method == "POST" && request.path.orEmpty().contains("/life/finance/expenses") -> json("{}")
                    else -> json(expensesJson) // GET /life/finance/expenses (initial + reload)
                }
            }
        }
        server.start()
        try {
            val content = E2eFx.onFx { LifeTab(E2eFx.apiClientFor(server)).tab.content }

            // Construction loads synchronously, so the list is already populated.
            assertTrue(E2eFx.onFx { E2eFx.hasText(content, "Loaded 2 expenses") }, "status should confirm 2 expenses")
            val items = E2eFx.onFx { E2eFx.find<ListView<*>>(content)?.items?.map { it.toString() } ?: emptyList() }
            assertTrue(items.any { it.contains("Food") }, "list should render the Food expense; got $items")

            // Fill the amount field and fire the real Add Expense button.
            E2eFx.onFx {
                val amountField = requireNotNull(
                    E2eFx.findAll<TextField>(content).firstOrNull { it.promptText == "Amount (EUR)" }
                ) { "amount field not found" }
                amountField.text = "7.25"
                button(content, "Add Expense").fire()
            }

            E2eFx.waitForFx(description = "expense posted") {
                calls.any { it.startsWith("POST") && it.contains("/life/finance/expenses") }
            }
            assertTrue(
                calls.any { it.startsWith("POST") && it.contains("/life/finance/expenses") },
                "add-expense should POST to the life finance endpoint; got $calls"
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `invalid amount is rejected without any network call`() {
        val server = MockWebServer()
        val calls = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls.add("${request.method} ${request.path}")
                return json("[]")
            }
        }
        server.start()
        try {
            val content = E2eFx.onFx { LifeTab(E2eFx.apiClientFor(server)).tab.content }
            val callsAfterLoad = calls.size

            E2eFx.onFx {
                val amountField = requireNotNull(
                    E2eFx.findAll<TextField>(content).firstOrNull { it.promptText == "Amount (EUR)" }
                ) { "amount field not found" }
                amountField.text = "not-a-number"
                button(content, "Add Expense").fire()
            }

            assertTrue(
                E2eFx.onFx { E2eFx.hasText(content, "Please enter a valid amount") },
                "invalid amount should surface a validation message"
            )
            assertTrue(calls.size == callsAfterLoad, "no extra request should fire for an invalid amount")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `start and stop timer buttons post to the time endpoints`() {
        val server = MockWebServer()
        val calls = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls.add("${request.method} ${request.path}")
                return json("{}")
            }
        }
        server.start()
        try {
            val content = E2eFx.onFx { LifeTab(E2eFx.apiClientFor(server)).tab.content }

            E2eFx.onFx { button(content, "Start Timer").fire() }
            assertTrue(E2eFx.onFx { E2eFx.hasText(content, "Timer started") }, "start should confirm timer start")

            E2eFx.onFx { button(content, "Stop Timer").fire() }
            assertTrue(E2eFx.onFx { E2eFx.hasText(content, "Timer stopped") }, "stop should confirm timer stop")

            assertTrue(
                calls.any { it.contains("/life/time/start") } && calls.any { it.contains("/life/time/stop") },
                "both timer endpoints should be hit; got $calls"
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty expense list shows the honest no-expenses placeholder`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = json("[]")
        }
        server.start()
        try {
            val content = E2eFx.onFx { LifeTab(E2eFx.apiClientFor(server)).tab.content }

            assertTrue(
                E2eFx.onFx { E2eFx.hasText(content, "No expenses recorded yet") },
                "empty payload should surface the no-expenses status"
            )
            val items = E2eFx.onFx { E2eFx.find<ListView<*>>(content)?.items?.map { it.toString() } ?: emptyList() }
            assertTrue(items.any { it.contains("No expenses yet") }, "list should show the empty placeholder; got $items")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a 404 from the life service degrades to a not-deployed message`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(404).setBody("not found")
        }
        server.start()
        try {
            val content = E2eFx.onFx { LifeTab(E2eFx.apiClientFor(server)).tab.content }

            assertTrue(
                E2eFx.onFx { E2eFx.hasText(content, "Life tracker service not deployed") },
                "a 404 should map to the not-deployed guidance"
            )
            val items = E2eFx.onFx { E2eFx.find<ListView<*>>(content)?.items?.map { it.toString() } ?: emptyList() }
            assertTrue(items.any { it.contains("No data available") }, "list should show the unavailable placeholder; got $items")
        } finally {
            server.shutdown()
        }
    }
}
