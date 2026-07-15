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
 * THIRD-wave headless-UI E2E journeys for [ScenesView], targeting non-modal
 * branches left uncovered by [ScenesViewE2eTest], [ScenesViewMoreE2eTest] and
 * [ScenesViewActivationE2eTest]:
 *
 *  - a scene JSON with no `name` field -> the read model's "Unnamed scene"
 *    default renders as the scene card title (existing tests always name scenes).
 *  - an activation response with the `results` key ENTIRELY ABSENT -> the
 *    `takeIf(JsonNode::isArray)`-returns-null branch of the summary, distinct
 *    from the empty-`results`-array case already covered (which returns the
 *    array and is then nulled by the size filter). Both surface "no steps
 *    applied", but via different branches.
 *
 * Both go through the non-modal Refresh / Activate buttons; the New-scene create
 * dialog and per-scene Delete confirm dialog (both `showAndWait`) are untouched.
 */
class ScenesViewThirdWaveE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun buttonNamed(root: Node, text: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == text }

    @Test
    fun `a scene with no name renders the Unnamed scene fallback title`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { ScenesView(E2eFx.apiClientFor(server)) }

            // The scene object omits "name" -> parseScene falls back to "Unnamed scene".
            server.enqueue(
                json("""[{"steps": [{"deviceId": "lamp-1", "action": "TURN_ON"}]}]""")
            )
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "unnamed scene card rendered") {
                E2eFx.hasText(view, "Unnamed scene") && E2eFx.hasText(view, "1 scene(s)")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "lamp-1"), "the scene's step still summarizes")
            }

            val req = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(req, "scenes list request should have reached the backend")
            assertEquals("GET", req!!.method)
            assertTrue(req.path!!.contains("/smarthome/scenes"), "path was ${req.path}")

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `activation with the results key absent reports no steps applied`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { ScenesView(E2eFx.apiClientFor(server)) }

            server.enqueue(json("""[{"name": "vacation", "steps": [{"deviceId": "lamp-1", "action": "TURN_OFF"}]}]"""))
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }
            E2eFx.waitForFx(description = "scene card rendered") { E2eFx.hasText(view, "vacation") }
            server.takeRequest(5, TimeUnit.SECONDS) // drain the list GET

            // No "results" key at all -> takeIf(JsonNode::isArray) yields null -> "no steps applied".
            server.enqueue(json("""{"applied": 4}"""))
            E2eFx.onFx { buttonNamed(view, "Activate").fire() }

            E2eFx.waitForFx(description = "missing-results fallback summary") {
                E2eFx.hasText(view, "no steps applied")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "4 step(s)"), "applied count still surfaced")
                assertTrue(E2eFx.hasText(view, "Activated"), "activation reported as Activated")
            }

            val req = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(req, "activate request should have reached the backend")
            assertEquals("POST", req!!.method)
            assertTrue(req.path!!.contains("/smarthome/scenes/vacation/activate"), "path was ${req.path}")

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }
}
