package org.jarvis.desktop.e2e.syncvision

import javafx.scene.control.Button
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.sync.SyncPairingView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * True UI end-to-end journeys for [SyncPairingView].
 *
 * Each test constructs the REAL view against a [MockWebServer] standing in for
 * the api-gateway, drives a real control (route activation or the Refresh
 * button), then asserts BOTH that the pairing probe reached the backend AND
 * that the visible scene graph (status pill / headline / raw status area)
 * reflected the outcome.
 */
class SyncPairingViewE2eTest {

    @Test
    fun `reachable pairing endpoint renders code and status in the raw area`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"paired":false,"pairingCode":"JARVIS-4821","status":"WAITING"}""")
        )
        server.start()
        try {
            val view = E2eFx.onFx { SyncPairingView(E2eFx.apiClientFor(server)) }
            // Route activation kicks off the async pairing-status probe.
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "reachable state rendered") {
                E2eFx.hasText(view, "Pairing endpoint reachable") &&
                    E2eFx.hasText(view, "JARVIS-4821")
            }

            // Visible scene graph reacted: success pill + headline + raw code.
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Reachable"), "status pill should read Reachable")
                assertTrue(E2eFx.hasText(view, "Pairing endpoint reachable"), "headline should confirm reachability")
                assertTrue(E2eFx.hasText(view, "JARVIS-4821"), "raw status area should show the pairing code")
                assertTrue(E2eFx.hasText(view, "WAITING"), "raw status area should show the pairing status")
            }

            // Backend received the pairing-status probe.
            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(req.path!!.contains("/sync/pairing/status"), "path was ${req.path}")

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `backend 500 renders honest unavailable state via Refresh button`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.start()
        try {
            val view = E2eFx.onFx { SyncPairingView(E2eFx.apiClientFor(server)) }

            // Drive the actual Refresh control instead of route activation.
            E2eFx.onFx {
                val refresh = E2eFx.findAll<Button>(view).first { it.text == "Refresh" }
                refresh.fire()
            }

            E2eFx.waitForFx(description = "degraded state rendered") {
                E2eFx.hasText(view, "Pairing временно недоступно")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Unavailable"), "status pill should read Unavailable")
                assertTrue(E2eFx.hasText(view, "Pairing временно недоступно"), "headline should show degraded state")
                assertTrue(
                    E2eFx.hasText(view, "not reachable yet"),
                    "detail should explain the sync route is not wired yet"
                )
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(req.path!!.contains("/sync/pairing/status"), "path was ${req.path}")

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }
}
