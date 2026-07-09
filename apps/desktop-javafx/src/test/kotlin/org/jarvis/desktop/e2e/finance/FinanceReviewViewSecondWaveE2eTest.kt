package org.jarvis.desktop.e2e.finance

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.TextArea
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.finance.FinanceReviewView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SECOND-WAVE headless-UI E2E journeys for [FinanceReviewView], targeting the
 * error/edge branches NOT covered by [FinanceReviewViewE2eTest] /
 * [FinanceReviewViewMoreE2eTest]:
 *
 *  - inbox Reject backend failure (DELETE 500 -> error status line)
 *  - client-queue Approve backend failure (POST expenses 500 -> Unavailable)
 *  - a MEDIUM-confidence client draft with a blank merchant, card mask and
 *    empty notes -> "unknown merchant" / "card: n/a" fallbacks + warning tone
 *  - a LOW-confidence inbox draft with a blank merchant, occurred date and
 *    notes -> "unknown merchant" / "occurred: unknown" fallbacks + error tone
 *
 * The Edit buttons open Dialog.showAndWait() modals (never returns headlessly),
 * so they are never fired. Every lookup roots at the ScrollPane's `content`.
 */
class FinanceReviewViewSecondWaveE2eTest {

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun contentOf(view: FinanceReviewView): Node =
        E2eFx.onFx { requireNotNull(view.content) { "FinanceReviewView content was not built" } }

    private fun buttonNamed(root: Node, label: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == label }

    @Test
    fun `rejecting an inbox draft surfaces an error when the DELETE fails`() {
        val server = MockWebServer()
        server.enqueue(
            jsonResponse(
                """
                {
                  "items": [
                    {"id": 42, "amount": "45.00", "currency": "PLN", "merchant": "Lidl",
                     "category": "groceries", "confidence": "MEDIUM", "status": "DRAFT",
                     "occurredAt": "2026-07-01", "notes": "low confidence parse"}
                  ],
                  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
                }
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom")) // DELETE fails
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }
            E2eFx.waitForFx(description = "inbox draft loaded") { E2eFx.hasText(root, "Lidl") }

            E2eFx.onFx { buttonNamed(root, "Reject").fire() }

            E2eFx.waitForFx(description = "reject failure surfaced") {
                E2eFx.hasText(root, "Server error (500)")
            }

            server.takeRequest() // initial GET
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertTrue(delete.path!!.contains("/api/v1/life/finance/review-inbox/42"), "path: ${delete.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `client-queue approve surfaces an error when persisting the expense fails`() {
        val server = MockWebServer()
        // 1: parse -> one draft needs review.
        server.enqueue(
            jsonResponse(
                """
                {
                  "imported": 0, "totalRows": 1,
                  "needsReview": [
                    {"confidence": "LOW", "needsReview": true, "amount": "12.30", "currency": "EUR",
                     "merchant": "Uber", "category": "transport", "cardMask": "*5678",
                     "occurredAt": "2026-07-02", "rawMasked": "…", "notes": ["merchant guessed"]}
                  ]
                }
                """.trimIndent()
            )
        )
        // 2: POST expenses (client-queue approve) fails.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"ledger offline"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            E2eFx.onFx {
                E2eFx.find<TextArea>(root)!!.text = "Płatność 12.30 EUR Uber ****5678"
                buttonNamed(root, "Parse batch").fire()
            }
            E2eFx.waitForFx(description = "parsed draft card rendered") {
                E2eFx.hasText(root, "Uber") && E2eFx.hasText(root, "need review")
            }

            E2eFx.onFx { buttonNamed(root, "Approve").fire() }

            E2eFx.waitForFx(description = "client-queue approve failure surfaced") {
                E2eFx.hasText(root, "Unavailable") && E2eFx.hasText(root, "Server error (500)")
            }
            E2eFx.onFx {
                // The draft survives a failed approve — the queue is not drained.
                assertTrue(E2eFx.hasText(root, "Uber"), "draft remains after a failed approve")
            }

            val parse = server.takeRequest()
            assertEquals("POST", parse.method)
            assertTrue(parse.path!!.contains("/import-csv-notifications"), "parse path: ${parse.path}")
            val approve = server.takeRequest()
            assertEquals("POST", approve.method)
            assertTrue(approve.path!!.contains("/api/v1/life/finance/expenses"), "approve path: ${approve.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a MEDIUM client draft with blank merchant, card and notes renders warning fallbacks`() {
        val server = MockWebServer()
        server.enqueue(
            jsonResponse(
                """
                {
                  "imported": 0, "totalRows": 1,
                  "needsReview": [
                    {"confidence": "MEDIUM", "needsReview": true, "amount": "9.99", "currency": "USD",
                     "merchant": "", "category": "misc", "cardMask": "",
                     "occurredAt": "", "rawMasked": "…", "notes": []}
                  ]
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            E2eFx.onFx {
                E2eFx.find<TextArea>(root)!!.text = "some unparseable line"
                buttonNamed(root, "Parse batch").fire()
            }

            E2eFx.waitForFx(description = "medium draft card rendered") {
                E2eFx.hasText(root, "9.99 USD")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "unknown merchant"), "blank merchant falls back to 'unknown merchant'")
                assertTrue(E2eFx.hasText(root, "card: n/a"), "blank card mask falls back to 'n/a'")
                assertTrue(E2eFx.hasText(root, "MEDIUM"), "MEDIUM confidence pill rendered")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a LOW-confidence inbox draft with blank merchant and date renders fallbacks`() {
        val server = MockWebServer()
        server.enqueue(
            jsonResponse(
                """
                {
                  "items": [
                    {"id": 77, "amount": "3.50", "currency": "GBP", "merchant": "",
                     "category": "uncategorized", "confidence": "LOW", "status": "DRAFT",
                     "occurredAt": "", "notes": ""}
                  ],
                  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "low-confidence inbox draft rendered") {
                E2eFx.hasText(root, "3.50 GBP")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "unknown merchant"), "blank merchant falls back to 'unknown merchant'")
                assertTrue(E2eFx.hasText(root, "occurred: unknown"), "blank occurred date falls back to 'unknown'")
                assertTrue(E2eFx.hasText(root, "LOW"), "LOW confidence pill rendered")
            }
        } finally {
            server.shutdown()
        }
    }
}
