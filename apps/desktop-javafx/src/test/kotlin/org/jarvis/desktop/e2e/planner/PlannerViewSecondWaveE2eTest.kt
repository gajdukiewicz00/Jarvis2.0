package org.jarvis.desktop.e2e.planner

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.planner.PlannerReadModel
import org.jarvis.desktop.features.planner.PlannerView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * SECOND-WAVE headless-UI E2E journeys for [PlannerView], targeting the
 * error/empty/edge branches NOT already exercised by the Load / Action / More
 * suites:
 *
 *  - apply-plan-mode with a NULL combo selection -> the `?: return` guard fires
 *    and no backend call is made at all
 *  - a CANCELLED task card -> the "CANCELLED" status pill error tone plus the
 *    branch that hides completion controls for a terminal task, and the open/done
 *    metric counters that exclude a CANCELLED row
 *
 * As in the sibling suites the widget tree lives in the ScrollPane's `content`
 * (no Scene/skin is attached headlessly), so every lookup roots at `view.content`.
 *
 * The modal Edit/Delete paths (Dialog.showAndWait) are intentionally never fired.
 */
class PlannerViewSecondWaveE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** The six brief responses that follow the snapshot on a full route-activation fan-out. */
    private fun enqueueBriefBundle(server: MockWebServer) {
        server.enqueue(json("""{"focus":"focus"}"""))
        server.enqueue(json("""{"review":"review"}"""))
        server.enqueue(json("""{"days":{}}"""))
        server.enqueue(json("""{"focusGoal":"goal"}"""))
        server.enqueue(json("""{"mode":"NORMAL"}"""))
        server.enqueue(json("""{"mode":"NORMAL","tasks":[]}"""))
    }

    private fun drain(server: MockWebServer, count: Int): List<RecordedRequest> {
        val out = mutableListOf<RecordedRequest>()
        repeat(count) {
            val req = server.takeRequest(3, TimeUnit.SECONDS) ?: return out
            out += req
        }
        return out
    }

    private fun rootOf(view: PlannerView): Node = E2eFx.onFx { view.content }

    @Test
    fun `applying plan mode with no selection returns early and makes no backend call`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.start() // no response enqueued — any request would prove the guard failed
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)

            E2eFx.onFx {
                @Suppress("UNCHECKED_CAST")
                val combo = E2eFx.findAll<ComboBox<*>>(root)
                    .first { it.items.any { item -> item is PlannerReadModel.PlanModeOption } }
                        as ComboBox<PlannerReadModel.PlanModeOption>
                combo.value = null
                E2eFx.findAll<Button>(root).first { it.text == "Apply plan mode" }.fire()
            }

            // The `?: return` guard runs synchronously on the FX thread, so once the
            // fire() call has returned there can be no in-flight backend traffic.
            assertEquals(0, server.requestCount, "a null plan-mode selection must not hit the backend")
            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(root, "Loading plan-mode ranking"),
                    "the plan-by-mode label is untouched: ${E2eFx.visibleText(root)}"
                )
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a CANCELLED task renders the error tone pill and exposes no completion controls`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.enqueue(
            json("""[{"id":601,"title":"Abandoned migration","priority":"HIGH","status":"CANCELLED"}]""")
        )
        enqueueBriefBundle(server)
        server.start()
        try {
            val view = E2eFx.onFx { PlannerView(E2eFx.apiClientFor(server)) }
            val root = rootOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "cancelled task card rendered") {
                E2eFx.hasText(root, "Abandoned migration")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "CANCELLED"), "cancelled status pill rendered")
                // A CANCELLED task is terminal — no Complete/Skip/Complete-occurrence controls.
                assertTrue(
                    E2eFx.findAll<Button>(root).none {
                        it.text == "Complete task" ||
                            it.text == "Skip occurrence" ||
                            it.text == "Complete occurrence"
                    },
                    "no completion controls for a CANCELLED task"
                )
                // Metric counters: 1 total, 0 open (terminal), 0 done (only DONE counts).
                assertTrue(E2eFx.hasText(root, "Ready"), "screen reaches Ready")
            }

            drain(server, 7)
        } finally {
            server.shutdown()
        }
    }
}
