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
 * Additional headless-UI E2E journeys for [FinanceReviewView] covering branches
 * not exercised by [FinanceReviewViewE2eTest]:
 *
 *  - the client-queue Reject button (drops a parsed draft, no backend call)
 *  - an inbox approval that resolves to a DUPLICATE expense
 *  - the inbox-approve error path (POST approve 500)
 *  - the batch-parse error path (POST import-csv-notifications 500)
 *
 * The Edit buttons (both client-queue and inbox) open `Dialog.showAndWait()`
 * modals whose nested loop never returns headlessly, so they are not fired.
 *
 * [FinanceReviewView] is a ScrollPane; headlessly its skin never builds, so
 * every lookup roots at `view.content`.
 */
class FinanceReviewViewMoreE2eTest {

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun jsonInboxWithOneDraft(): String =
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

    private fun contentOf(view: FinanceReviewView): Node =
        E2eFx.onFx { requireNotNull(view.content) { "FinanceReviewView content was not built" } }

    private fun buttonNamed(root: Node, label: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == label }

    @Test
    fun `rejecting a parsed client-queue draft drops it without any backend call`() {
        val server = MockWebServer()
        // Only the parse call hits the backend; Reject is client-side.
        server.enqueue(
            jsonResponse(
                """
                {
                  "imported": 0,
                  "totalRows": 1,
                  "needsReview": [
                    {"confidence": "LOW", "needsReview": true, "amount": "12.30", "currency": "EUR",
                     "merchant": "Uber", "category": "transport", "cardMask": "*5678",
                     "occurredAt": "2026-07-02", "rawMasked": "…", "notes": ["merchant guessed"]}
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
                E2eFx.find<TextArea>(root)!!.text = "Płatność 12.30 EUR Uber ****5678"
                buttonNamed(root, "Parse batch").fire()
            }
            E2eFx.waitForFx(description = "parsed draft card rendered") {
                E2eFx.hasText(root, "Uber") && E2eFx.hasText(root, "need review")
            }

            // Fire the client-queue Reject on the parsed draft.
            E2eFx.onFx { buttonNamed(root, "Reject").fire() }

            E2eFx.waitForFx(description = "reject drained the queue") {
                E2eFx.hasText(root, "Draft rejected") && E2eFx.hasText(root, "No drafts awaiting review")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "nothing was persisted"), "reject stresses nothing stored")
            }

            // Exactly one backend call — the parse. Reject made none.
            assertEquals(1, server.requestCount, "client-queue reject must not hit the backend")
            val parse = server.takeRequest()
            assertEquals("POST", parse.method)
            assertTrue(parse.path!!.contains("/api/v1/life/finance/import-csv-notifications"), "path: ${parse.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `approving an inbox draft that is a duplicate reports the duplicate outcome`() {
        val server = MockWebServer()
        server.enqueue(jsonResponse(jsonInboxWithOneDraft()))                                    // 1: initial GET
        server.enqueue(
            jsonResponse(
                """{"duplicate":true,"expense":{"amount":"45.00","currency":"PLN","merchant":"Lidl"}}"""
            )
        )                                                                                         // 2: POST approve
        server.enqueue(jsonResponse("""{"items":[],"page":0,"size":20,"totalElements":0,"totalPages":1}"""))  // 3: reload GET
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }
            E2eFx.waitForFx(description = "inbox draft loaded") { E2eFx.hasText(root, "Lidl") }

            E2eFx.onFx { buttonNamed(root, "Approve").fire() }

            E2eFx.waitForFx(description = "duplicate outcome reported") {
                E2eFx.hasText(root, "Duplicate of an existing expense")
            }
            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(root, "no new row created"),
                    "duplicate message explains nothing new was stored: ${E2eFx.visibleText(root)}"
                )
            }

            server.takeRequest() // initial GET
            val approve = server.takeRequest()
            assertEquals("POST", approve.method)
            assertTrue(
                approve.path!!.contains("/api/v1/life/finance/review-inbox/42/approve"),
                "approve path: ${approve.path}"
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an inbox approve failure surfaces an error status`() {
        val server = MockWebServer()
        server.enqueue(jsonResponse(jsonInboxWithOneDraft()))                     // 1: initial GET
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))       // 2: POST approve fails
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }
            E2eFx.waitForFx(description = "inbox draft loaded") { E2eFx.hasText(root, "Lidl") }

            E2eFx.onFx { buttonNamed(root, "Approve").fire() }

            E2eFx.waitForFx(description = "approve failure status shown") {
                E2eFx.hasText(root, "Server error (500)")
            }

            server.takeRequest() // initial GET
            val approve = server.takeRequest()
            assertEquals("POST", approve.method)
            assertTrue(approve.path!!.contains("/api/v1/life/finance/review-inbox/42/approve"), "path: ${approve.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a failing batch parse surfaces an unavailable status and error line`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"parser offline"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            E2eFx.onFx {
                E2eFx.find<TextArea>(root)!!.text = "Płatność 12.30 EUR Uber ****5678"
                buttonNamed(root, "Parse batch").fire()
            }

            E2eFx.waitForFx(description = "parse failure surfaced") {
                E2eFx.hasText(root, "Unavailable") && E2eFx.hasText(root, "Server error (500)")
            }

            val parse = server.takeRequest()
            assertEquals("POST", parse.method)
            assertTrue(parse.path!!.contains("/api/v1/life/finance/import-csv-notifications"), "path: ${parse.path}")
        } finally {
            server.shutdown()
        }
    }
}
