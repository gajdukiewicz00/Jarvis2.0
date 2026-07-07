package org.jarvis.desktop.e2e.finance

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
 * TRUE UI end-to-end journeys for the Finance draft-review inbox.
 *
 * Drives the real [FinanceReviewView] scene graph (Refresh inbox button, the
 * per-draft Approve / Reject buttons, and the batch-parser TextArea + Parse
 * button) against a [MockWebServer] standing in for the api-gateway, then
 * asserts BOTH that the expected HTTP request(s) reached the backend AND that
 * the visible widget tree reacted (inbox draft cards, status labels, placeholders).
 *
 * ApiClient prefixes every endpoint with `/api/v1`, so the persisted-inbox
 * surface hits `/api/v1/life/finance/review-inbox...`.
 */
class FinanceReviewViewE2eTest {

    private fun jsonInboxWithOneDraft(): String =
        """
        {
          "items": [
            {
              "id": 42,
              "amount": "45.00",
              "currency": "PLN",
              "merchant": "Lidl",
              "category": "groceries",
              "confidence": "MEDIUM",
              "status": "DRAFT",
              "occurredAt": "2026-07-01",
              "notes": "low confidence parse"
            }
          ],
          "page": 0,
          "size": 20,
          "totalElements": 1,
          "totalPages": 1
        }
        """.trimIndent()

    private fun jsonEmptyInbox(): String =
        """{"items":[],"page":0,"size":20,"totalElements":0,"totalPages":1}"""

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** Find a wired action button by its exact label. Call on the FX thread. */
    private fun buttonNamed(view: FinanceReviewView, label: String): Button =
        E2eFx.findAll<Button>(view).first { it.text == label }

    // ---------------------------------------------------------------------
    // Persisted review-inbox: happy load
    // ---------------------------------------------------------------------

    @Test
    fun `happy path - onRouteActivated loads the persisted review inbox and renders draft cards`() {
        val server = MockWebServer()
        server.enqueue(jsonResponse(jsonInboxWithOneDraft()))
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }

            // Before activation the inbox shows its refresh placeholder.
            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view, "Refresh to load the persisted review inbox"),
                    "inbox placeholder should be visible before activation"
                )
            }

            // Route activation triggers the async GET + Platform.runLater render.
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "persisted inbox draft card rendered") {
                E2eFx.hasText(view, "Lidl") && E2eFx.hasText(view, "awaiting review")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "45.00 PLN"), "amount+currency should surface in the card title")
                assertTrue(E2eFx.hasText(view, "groceries"), "category should surface in the card subtitle")
                assertTrue(E2eFx.hasText(view, "low confidence parse"), "notes should surface")
                assertTrue(
                    E2eFx.hasText(view, "1 draft(s) awaiting review"),
                    "inbox status label should report the count: ${E2eFx.visibleText(view)}"
                )
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(
                req.path!!.contains("/api/v1/life/finance/review-inbox"),
                "path was ${req.path}"
            )
            assertTrue(req.path!!.contains("page=0"), "should page from 0: ${req.path}")
            assertTrue(req.path!!.contains("size=20"), "should request page size 20: ${req.path}")
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // Persisted review-inbox: approve a draft
    // ---------------------------------------------------------------------

    @Test
    fun `happy path - approving an inbox draft posts approve then reloads to an empty queue`() {
        val server = MockWebServer()
        server.enqueue(jsonResponse(jsonInboxWithOneDraft()))                                   // 1: initial GET
        server.enqueue(
            jsonResponse("""{"duplicate":false,"expense":{"amount":"45.00","currency":"PLN","merchant":"Lidl"}}""")
        )                                                                                        // 2: POST approve
        server.enqueue(jsonResponse(jsonEmptyInbox()))                                           // 3: reload GET
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }
            E2eFx.waitForFx(description = "inbox draft loaded before approve") {
                E2eFx.hasText(view, "Lidl")
            }

            // Fire the real Approve button on the draft card.
            E2eFx.onFx { buttonNamed(view, "Approve").fire() }

            E2eFx.waitForFx(description = "approve outcome + reload settled") {
                E2eFx.hasText(view, "Approved and stored as an expense")
            }

            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view, "45.00 PLN Lidl"),
                    "approval status should echo the persisted expense summary: ${E2eFx.visibleText(view)}"
                )
                // Reloaded queue is empty -> placeholder replaces the card.
                assertTrue(
                    E2eFx.hasText(view, "No persisted drafts awaiting review"),
                    "empty-queue placeholder should show after reload"
                )
            }

            val initialGet = server.takeRequest()
            assertEquals("GET", initialGet.method)
            assertTrue(initialGet.path!!.contains("/api/v1/life/finance/review-inbox"), "path was ${initialGet.path}")

            val approve = server.takeRequest()
            assertEquals("POST", approve.method)
            assertTrue(
                approve.path!!.contains("/api/v1/life/finance/review-inbox/42/approve"),
                "approve should target the draft id: ${approve.path}"
            )

            val reload = server.takeRequest()
            assertEquals("GET", reload.method)
            assertTrue(reload.path!!.contains("/api/v1/life/finance/review-inbox"), "reload path was ${reload.path}")
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // Persisted review-inbox: reject a draft
    // ---------------------------------------------------------------------

    @Test
    fun `happy path - rejecting an inbox draft issues a DELETE then reloads`() {
        val server = MockWebServer()
        server.enqueue(jsonResponse(jsonInboxWithOneDraft()))   // 1: initial GET
        server.enqueue(MockResponse().setBody(""))              // 2: DELETE (204/200 no body)
        server.enqueue(jsonResponse(jsonEmptyInbox()))          // 3: reload GET
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }
            E2eFx.waitForFx(description = "inbox draft loaded before reject") {
                E2eFx.hasText(view, "Lidl")
            }

            E2eFx.onFx { buttonNamed(view, "Reject").fire() }

            E2eFx.waitForFx(description = "reject settled") {
                E2eFx.hasText(view, "Draft rejected")
            }

            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view, "nothing was persisted"),
                    "reject status should stress that nothing was stored: ${E2eFx.visibleText(view)}"
                )
                assertTrue(E2eFx.hasText(view, "No persisted drafts awaiting review"))
            }

            server.takeRequest() // initial GET
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertTrue(
                delete.path!!.contains("/api/v1/life/finance/review-inbox/42"),
                "delete should target the draft id: ${delete.path}"
            )
            assertTrue(
                !delete.path!!.contains("/approve"),
                "reject must not hit the approve sub-path: ${delete.path}"
            )
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // Persisted review-inbox: backend error
    // ---------------------------------------------------------------------

    @Test
    fun `error path - inbox load 500 surfaces an error status and error placeholder`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"life-tracker offline"}""")
        )
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "error placeholder rendered") {
                E2eFx.hasText(view, "Unable to load the review inbox")
            }

            E2eFx.onFx {
                // ApiClient maps 5xx to a "Server error (500)" message shown in the status label.
                assertTrue(
                    E2eFx.hasText(view, "Server error (500)"),
                    "status label should show the mapped 5xx error: ${E2eFx.visibleText(view)}"
                )
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(req.path!!.contains("/api/v1/life/finance/review-inbox"), "path was ${req.path}")
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // Persisted review-inbox: empty payload
    // ---------------------------------------------------------------------

    @Test
    fun `edge path - empty inbox payload renders the no-drafts placeholder`() {
        val server = MockWebServer()
        server.enqueue(jsonResponse(jsonEmptyInbox()))
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "empty-inbox placeholder rendered") {
                E2eFx.hasText(view, "No persisted drafts awaiting review")
            }

            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view, "0 draft(s) awaiting review"),
                    "status label should report zero drafts: ${E2eFx.visibleText(view)}"
                )
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(req.path!!.contains("/api/v1/life/finance/review-inbox"), "path was ${req.path}")
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // Client-side batch queue: parse a batch, then approve a parsed draft
    // ---------------------------------------------------------------------

    @Test
    fun `happy path - parsing a batch populates the review queue then approving stores an expense`() {
        val server = MockWebServer()
        // 1: POST import-csv-notifications -> one draft needs review.
        server.enqueue(
            jsonResponse(
                """
                {
                  "imported": 1,
                  "totalRows": 2,
                  "needsReview": [
                    {
                      "confidence": "LOW",
                      "needsReview": true,
                      "amount": "12.30",
                      "currency": "EUR",
                      "merchant": "Uber",
                      "category": "transport",
                      "cardMask": "*5678",
                      "occurredAt": "2026-07-02",
                      "rawMasked": "Płatność 12.30 EUR Uber ****5678",
                      "notes": ["merchant guessed"]
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        // 2: POST expenses (client-queue approve persists the draft).
        server.enqueue(jsonResponse("""{"id":7,"amount":12.30,"currency":"EUR","category":"transport"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }

            // Type raw notifications into the batch TextArea and fire Parse batch.
            E2eFx.onFx {
                E2eFx.find<TextArea>(view)!!.text = "Płatność 12.30 EUR Uber ****5678\nПокупка 45,00 zł w Lidl karta *1234"
                buttonNamed(view, "Parse batch").fire()
            }

            E2eFx.waitForFx(description = "parsed draft card rendered in the review queue") {
                E2eFx.hasText(view, "Uber") && E2eFx.hasText(view, "need review")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "12.30 EUR"), "parsed amount+currency should surface")
                assertTrue(
                    E2eFx.hasText(view, "Imported 1/2 automatically"),
                    "parse summary should report imported vs review counts: ${E2eFx.visibleText(view)}"
                )
            }

            // Approve the parsed (client-queue) draft -> POST /life/finance/expenses.
            E2eFx.onFx { buttonNamed(view, "Approve").fire() }

            E2eFx.waitForFx(description = "client-queue approve settled") {
                E2eFx.hasText(view, "Approved and stored as an expense")
            }

            E2eFx.onFx {
                // Queue drained back to its empty placeholder after approve.
                assertTrue(
                    E2eFx.hasText(view, "No drafts awaiting review"),
                    "review queue should drain after approving the only draft: ${E2eFx.visibleText(view)}"
                )
            }

            val parse = server.takeRequest()
            assertEquals("POST", parse.method)
            assertTrue(
                parse.path!!.contains("/api/v1/life/finance/import-csv-notifications"),
                "parse path was ${parse.path}"
            )
            val parseBody = parse.body.readUtf8()
            assertTrue(parseBody.contains("\"csv\""), "batch body should carry the csv field: $parseBody")
            assertTrue(parseBody.contains("Uber"), "batch body should carry the pasted notifications: $parseBody")

            val approve = server.takeRequest()
            assertEquals("POST", approve.method)
            assertTrue(
                approve.path!!.contains("/api/v1/life/finance/expenses"),
                "client-queue approve should persist an expense: ${approve.path}"
            )
            val approveBody = approve.body.readUtf8()
            assertTrue(approveBody.contains("bank: Uber"), "expense description should carry the merchant: $approveBody")
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------
    // Client-side batch queue: blank input is rejected without a backend call
    // ---------------------------------------------------------------------

    @Test
    fun `edge path - parsing a blank batch warns and makes no backend call`() {
        val server = MockWebServer()
        server.start() // no response enqueued: any request would prove a leak
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }

            E2eFx.onFx {
                E2eFx.find<TextArea>(view)!!.text = "   "
                buttonNamed(view, "Parse batch").fire()
            }

            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view, "Paste at least one notification first"),
                    "blank batch should warn in the parse result: ${E2eFx.visibleText(view)}"
                )
            }

            assertEquals(0, server.requestCount, "blank batch parse must not hit the backend")
        } finally {
            server.shutdown()
        }
    }
}
