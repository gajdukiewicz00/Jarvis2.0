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
 * Additional headless-UI E2E journeys for [ScenesView] covering branches not
 * exercised by [ScenesViewE2eTest]:
 *
 *  - activation error path (POST .../activate 500 -> Unavailable status)
 *  - a scene with no configured steps -> "No steps configured." summary
 *
 * The New-scene create dialog and the per-scene Delete confirm dialog both call
 * `Dialog.showAndWait()`, whose nested modal loop never returns headlessly, so
 * neither is fired here (the task brief calls out the delete-confirm modal).
 */
class ScenesViewMoreE2eTest {

    private fun buttonNamed(root: Node, text: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == text }

    @Test
    fun `activation failure surfaces an error status without crashing`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { ScenesView(E2eFx.apiClientFor(server)) }

            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """[{"name": "wake-up", "steps": [{"deviceId": "lamp-1", "action": "TURN_ON"}]}]"""
                )
            )
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }
            E2eFx.waitForFx(description = "scene card rendered") { E2eFx.hasText(view, "wake-up") }
            server.takeRequest(5, TimeUnit.SECONDS) // drain the list GET

            // Activate now fails on the backend.
            server.enqueue(MockResponse().setResponseCode(500).setBody("kaboom"))
            E2eFx.onFx { buttonNamed(view, "Activate").fire() }

            E2eFx.waitForFx(description = "activation error status reflected") {
                E2eFx.hasText(view, "Unavailable")
            }

            val activateReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(activateReq, "activate request should still have been attempted")
            assertEquals("POST", activateReq!!.method)
            assertTrue(
                activateReq.path!!.contains("/smarthome/scenes/wake-up/activate"),
                "path was ${activateReq.path}"
            )

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a scene with no steps renders the no-steps summary`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { ScenesView(E2eFx.apiClientFor(server)) }

            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """[{"name": "empty-scene", "steps": []}]"""
                )
            )
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "empty-steps scene rendered") {
                E2eFx.hasText(view, "empty-scene") && E2eFx.hasText(view, "No steps configured")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "1 scene(s)"), "one scene reported")
            }

            val req = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(req, "scenes list request should have reached the backend")
            assertEquals("GET", req!!.method)

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }
}
