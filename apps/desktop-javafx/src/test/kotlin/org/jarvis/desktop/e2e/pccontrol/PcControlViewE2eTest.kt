package org.jarvis.desktop.e2e.pccontrol

import javafx.scene.control.Button
import javafx.scene.control.TextField
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.pccontrol.PcControlView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * True headless-JavaFX UI E2E journeys for the PC Control screen.
 *
 * IMPORTANT — reality vs. the assigned brief:
 * The brief described a policy-gated `/api/v1/pc/desktop/...` dispatch flow
 * (SAFE runs / GUARDED-DANGEROUS confirm). That flow does NOT exist in this
 * codebase. The real [PcControlView] hosts the legacy `PcControlTab`, whose
 * volume/media/app/hotkey/window/scenario buttons drive the LOCAL desktop via
 * `SystemControlService` (pactl / playerctl / xdotool / notify-send
 * subprocesses) — real host hardware that Monocle cannot provide and that must
 * not be fired in a test. The single control that actually reaches the backend
 * is the "Text Command" box: its "Send to Orchestrator" button POSTs
 * `{"text": "..."}` to `/orchestrator/execute` (resolved to
 * `/api/v1/orchestrator/execute`) and then reflects success/failure in the
 * visible status label. These tests drive that real control end to end and
 * assert BOTH the MockWebServer request AND the scene-graph reaction.
 */
class PcControlViewE2eTest {

    private val sendButtonLabel = "Send to Orchestrator"

    private fun sendButton(view: PcControlView): Button =
        E2eFx.findAll<Button>(view).first { it.text == sendButtonLabel }

    private fun commandField(view: PcControlView): TextField =
        requireNotNull(E2eFx.find<TextField>(view)) { "PC Control text-command field not found" }

    @Test
    fun `text command happy path posts to orchestrator and status label confirms`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"accepted"}""")
        )
        server.start()
        try {
            val view = E2eFx.onFx { PcControlView(E2eFx.apiClientFor(server)) }
            // Exercise the ShellRouteContent contract (refreshes local capabilities).
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.onFx {
                commandField(view).text = "increase volume"
                sendButton(view).fire()
            }

            // Handler runs synchronously on the FX thread, but poll to stay robust.
            E2eFx.waitForFx(description = "status label confirms command sent") {
                E2eFx.hasText(view, "Command sent")
            }

            // Backend received the right call.
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(
                request.path!!.contains("/api/v1/orchestrator/execute"),
                "unexpected path: ${request.path}"
            )
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"text\""), "body missing text field: $body")
            assertTrue(body.contains("increase volume"), "body missing command text: $body")

            // Visible scene graph reacted: success status shown and field cleared.
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "increase volume") || E2eFx.hasText(view, "Command sent"))
                assertEquals("", commandField(view).text, "field should clear after a successful send")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `text command backend 500 shows failure status and keeps the typed text`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.start()
        try {
            val view = E2eFx.onFx { PcControlView(E2eFx.apiClientFor(server)) }

            E2eFx.onFx {
                commandField(view).text = "trigger error"
                sendButton(view).fire()
            }

            // The handler catches the ApiClient exception and renders a failure.
            E2eFx.waitForFx(description = "status label shows failure") {
                E2eFx.hasText(view, "Failed")
            }

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/api/v1/orchestrator/execute"))

            // On failure the success confirmation must NOT appear and the typed
            // command is preserved so the user can retry.
            E2eFx.onFx {
                assertFalse(E2eFx.hasText(view, "Command sent"), "must not show success on 500")
                assertEquals("trigger error", commandField(view).text, "field must not clear on failure")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `blank text command is guarded and never reaches the backend`() {
        val server = MockWebServer()
        // Intentionally enqueue nothing — a blank command must not fire a request.
        server.start()
        try {
            val view = E2eFx.onFx { PcControlView(E2eFx.apiClientFor(server)) }

            E2eFx.onFx {
                commandField(view).text = "   "
                sendButton(view).fire()
            }

            // No async work is triggered; assert the guard held.
            E2eFx.onFx {
                assertEquals(0, server.requestCount, "blank command must not hit the backend")
                assertFalse(E2eFx.hasText(view, "Command sent"), "no success status for a blank command")
            }
        } finally {
            server.shutdown()
        }
    }
}
