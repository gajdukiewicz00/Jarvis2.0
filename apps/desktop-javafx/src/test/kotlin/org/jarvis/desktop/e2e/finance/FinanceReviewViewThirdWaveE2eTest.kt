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
 * THIRD-WAVE headless-UI E2E journeys for [FinanceReviewView], targeting the
 * batch-parser edge NOT covered by the earlier suites (which always parse a
 * batch that yields at least one draft needing review).
 *
 * Here every row is auto-imported by the backend and NONE need review, so the
 * client review queue drains to its empty placeholder ("No drafts awaiting
 * review.") and the parse summary reports "0 need review" — the false side of
 * the [renderDrafts] emptiness branch reached via a successful parse.
 *
 * The Edit buttons open Dialog.showAndWait() modals (never returns headlessly),
 * so they are never fired. Every lookup roots at the ScrollPane's `content`.
 */
class FinanceReviewViewThirdWaveE2eTest {

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun contentOf(view: FinanceReviewView): Node =
        E2eFx.onFx { requireNotNull(view.content) { "FinanceReviewView content was not built" } }

    private fun buttonNamed(root: Node, label: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == label }

    @Test
    fun `parsing a batch that needs no review drains the queue to its empty placeholder`() {
        val server = MockWebServer()
        // Everything auto-imported HIGH-confidence; needsReview is empty.
        server.enqueue(jsonResponse("""{"imported":3,"totalRows":3,"needsReview":[]}"""))
        server.start()
        try {
            val view = E2eFx.onFx { FinanceReviewView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            E2eFx.onFx {
                E2eFx.find<TextArea>(root)!!.text =
                    "Purchase 10.00 EUR Rossmann *1111\nPurchase 20.00 EUR Biedronka *2222\nPurchase 30.00 EUR Zabka *3333"
                buttonNamed(root, "Parse batch").fire()
            }

            E2eFx.waitForFx(description = "parse summary reports zero needing review") {
                E2eFx.hasText(root, "0 need review")
            }

            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(root, "Imported 3/3 automatically"),
                    "parse summary reports the auto-imported count: ${E2eFx.visibleText(root)}"
                )
                assertTrue(
                    E2eFx.hasText(root, "No drafts awaiting review"),
                    "the empty review queue shows its no-drafts placeholder"
                )
                assertTrue(E2eFx.hasText(root, "Ready"), "status pill flips to Ready after a successful parse")
            }

            val parse = server.takeRequest()
            assertEquals("POST", parse.method)
            assertTrue(
                parse.path!!.contains("/api/v1/life/finance/import-csv-notifications"),
                "parse path was ${parse.path}"
            )
        } finally {
            server.shutdown()
        }
    }
}
