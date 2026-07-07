package org.jarvis.desktop.e2e.finance

import javafx.scene.control.Button
import javafx.scene.control.TextField
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.finance.FinanceView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TRUE UI end-to-end journeys for the Finance panel.
 *
 * Drives the real [FinanceView] scene graph (Refresh button, the add-expense
 * TextFields + Add button) against a [MockWebServer], asserting BOTH that the
 * expected life-tracker finance request reached the backend AND that the
 * visible transaction cards / status text reacted.
 *
 * ApiClient prefixes endpoints with `/api/v1`, so requests hit
 * `/api/v1/life/finance/expenses`.
 */
class FinanceViewE2eTest {

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun buttonNamed(view: FinanceView, label: String): Button =
        E2eFx.findAll<Button>(view).first { it.text == label }

    private fun fieldWithPrompt(view: FinanceView, prompt: String): TextField =
        E2eFx.findAll<TextField>(view).first { it.promptText == prompt }

    // ---------------------------------------------------------------------
    // Basic load
    // ---------------------------------------------------------------------

    @Test
    fun `happy path - onRouteActivated loads recent transactions into the panel`() {
        val server = MockWebServer()
        server.enqueue(
            jsonResponse(
                """
                [
                  {"amount":"12.30","currency":"EUR","category":"transport","description":"Uber","createdAt":"2026-07-01"}
                ]
                """.trimIndent()
            )
        )
        server.start()
        try {
            val view = E2eFx.onFx { FinanceView(E2eFx.apiClientFor(server)) }

            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view, "Refresh to load recent transactions"),
                    "transactions placeholder should be visible before activation"
                )
            }

            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "transaction card rendered") {
                E2eFx.hasText(view, "12.30 EUR")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Uber"), "description should surface in the card meta")
                assertTrue(E2eFx.hasText(view, "transport"), "category pill should surface")
                assertTrue(E2eFx.hasText(view, "Done."), "status label should settle after load")
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(req.path!!.contains("/api/v1/life/finance/expenses"), "path was ${req.path}")
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // Add expense (POST then reload)
    // ---------------------------------------------------------------------

    @Test
    fun `happy path - adding an expense posts it then refreshes the transaction list`() {
        val server = MockWebServer()
        server.enqueue(jsonResponse("""{"id":9,"amount":"19.99","currency":"RUB","category":"food"}""")) // 1: POST add
        server.enqueue(
            jsonResponse(
                """[{"amount":"19.99","currency":"RUB","category":"food","description":"Coffee","createdAt":"2026-07-03"}]"""
            )
        ) // 2: GET list
        server.start()
        try {
            val view = E2eFx.onFx { FinanceView(E2eFx.apiClientFor(server)) }

            // Fill the add-expense form and fire the real Add button.
            E2eFx.onFx {
                fieldWithPrompt(view, "Amount").text = "19.99"
                fieldWithPrompt(view, "Category").text = "food"
                fieldWithPrompt(view, "Description (optional)").text = "Coffee"
                buttonNamed(view, "Add expense").fire()
            }

            E2eFx.waitForFx(description = "added expense reflected in the list") {
                E2eFx.hasText(view, "19.99 RUB") && E2eFx.hasText(view, "Coffee")
            }

            val add = server.takeRequest()
            assertEquals("POST", add.method)
            assertTrue(add.path!!.contains("/api/v1/life/finance/expenses"), "add path was ${add.path}")
            val addBody = add.body.readUtf8()
            assertTrue(addBody.contains("19.99"), "posted body should carry the amount: $addBody")

            val reload = server.takeRequest()
            assertEquals("GET", reload.method)
            assertTrue(reload.path!!.contains("/api/v1/life/finance/expenses"), "reload path was ${reload.path}")
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // Error: backend 500 on load
    // ---------------------------------------------------------------------

    @Test
    fun `error path - transactions load 500 surfaces an unavailable status`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"life-tracker offline"}""")
        )
        server.start()
        try {
            val view = E2eFx.onFx { FinanceView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "error status surfaced") {
                E2eFx.hasText(view, "Unavailable")
            }

            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view, "Server error (500)"),
                    "status label should show the mapped 5xx error: ${E2eFx.visibleText(view)}"
                )
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(req.path!!.contains("/api/v1/life/finance/expenses"), "path was ${req.path}")
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // Edge: blank amount is rejected client-side without a backend call
    // ---------------------------------------------------------------------

    @Test
    fun `edge path - adding an expense with a blank amount warns and makes no backend call`() {
        val server = MockWebServer()
        server.start() // no response enqueued: a request would prove a leak
        try {
            // Do NOT activate the route, so no load fires; isolate the add validation.
            val view = E2eFx.onFx { FinanceView(E2eFx.apiClientFor(server)) }

            E2eFx.onFx {
                fieldWithPrompt(view, "Amount").text = "   "
                buttonNamed(view, "Add expense").fire()
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Input needed"), "status pill should warn on invalid amount")
                assertTrue(
                    E2eFx.hasText(view, "Enter a valid numeric amount"),
                    "status label should explain the validation failure: ${E2eFx.visibleText(view)}"
                )
            }

            assertEquals(0, server.requestCount, "blank-amount add must not hit the backend")
        } finally {
            server.shutdown()
        }
    }
}
