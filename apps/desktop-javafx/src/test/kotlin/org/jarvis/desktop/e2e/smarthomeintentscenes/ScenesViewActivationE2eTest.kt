package org.jarvis.desktop.e2e.smarthomeintentscenes

import javafx.scene.Node
import javafx.scene.control.Button
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.smarthome.ScenesView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Additional [ScenesView] activation journeys that exercise the summary branches
 * of a SUCCESSFUL activation not covered by [ScenesViewE2eTest] (which only hits
 * the fully-resolved device/action branch) or [ScenesViewMoreE2eTest] (the 500
 * error path):
 *
 *  - a per-step `error` in the results -> "<device> failed: <error>" summary
 *  - `applied` count with an empty `results` array -> "no steps applied" fallback
 *  - a result node with neither device/action nor error -> "step ok" summary
 *
 * All go through the non-modal Activate button; the New-scene create dialog and
 * per-scene Delete confirm dialog (both showAndWait) are deliberately untouched.
 */
class ScenesViewActivationE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun buttonNamed(root: Node, text: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == text }

    private fun loadSingleScene(server: MockWebServer, view: ScenesView, name: String) {
        server.enqueue(json("""[{"name": "$name", "steps": [{"deviceId": "lamp-1", "action": "TURN_ON"}]}]"""))
        E2eFx.onFx { buttonNamed(view, "Refresh").fire() }
        E2eFx.waitForFx(description = "scene card rendered") { E2eFx.hasText(view, name) }
        server.takeRequest(5, TimeUnit.SECONDS) // drain the list GET
    }

    @Test
    fun `activation summarizes a per-step device error`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { ScenesView(E2eFx.apiClientFor(server)) }
            loadSingleScene(server, view, "night-mode")

            // applied=0, one step reports an error -> summarizeStepResult error branch.
            server.enqueue(
                json("""{"applied": 0, "results": [{"deviceId": "lamp-1", "error": "device offline"}]}""")
            )
            E2eFx.onFx { buttonNamed(view, "Activate").fire() }

            E2eFx.waitForFx(description = "per-step error summarized") {
                E2eFx.hasText(view, "lamp-1 failed: device offline")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Activated"), "pill still flips to Activated on a soft-degraded run")
                assertTrue(E2eFx.hasText(view, "0 step(s)"), "applied count surfaced")
            }

            val req = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(req, "activate request should have reached the backend")
            assertEquals("POST", req!!.method)
            assertTrue(req.path!!.contains("/smarthome/scenes/night-mode/activate"), "path was ${req.path}")

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `activation with an empty results array reports no steps applied`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { ScenesView(E2eFx.apiClientFor(server)) }
            loadSingleScene(server, view, "away")

            // applied present but results empty -> the "no steps applied" fallback summary.
            server.enqueue(json("""{"applied": 3, "results": []}"""))
            E2eFx.onFx { buttonNamed(view, "Activate").fire() }

            E2eFx.waitForFx(description = "no-steps-applied fallback summary") {
                E2eFx.hasText(view, "no steps applied")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "3 step(s)"), "applied count still surfaced")
                assertTrue(E2eFx.hasText(view, "Activated"), "activation reported as Activated")
            }

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `activation of a bare result node summarizes as step ok`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { ScenesView(E2eFx.apiClientFor(server)) }
            loadSingleScene(server, view, "reading")

            // A result with no device/action and no error -> the "step ok" branch.
            server.enqueue(json("""{"applied": 1, "results": [{}]}"""))
            E2eFx.onFx { buttonNamed(view, "Activate").fire() }

            E2eFx.waitForFx(description = "bare step summarized as ok") {
                E2eFx.hasText(view, "step ok")
            }
            E2eFx.onFx { assertTrue(E2eFx.hasText(view, "1 step(s)"), "applied count surfaced") }

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }
}
