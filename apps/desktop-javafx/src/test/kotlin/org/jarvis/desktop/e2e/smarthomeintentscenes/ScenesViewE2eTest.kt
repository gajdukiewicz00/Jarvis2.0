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
 * True headless-UI E2E journeys for [ScenesView] (Smart Home "Scenes" panel).
 * Drives the real Refresh / per-scene Activate buttons against a
 * [MockWebServer] and asserts both the rendered scene cards / status text and
 * the backend requests.
 */
class ScenesViewE2eTest {

    private fun buttonNamed(root: Node, text: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == text }

    @Test
    fun `list scenes then activate one`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { ScenesView(E2eFx.apiClientFor(server)) }

            // --- Journey step 1: load the scene list ---
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        [
                          {"name": "movie-night", "steps": [
                            {"deviceId": "lamp-1", "action": "DIM", "payload": "20"},
                            {"deviceId": "lock-1", "action": "LOCK"}
                          ]}
                        ]
                        """.trimIndent()
                    )
            )
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "scene card rendered") {
                E2eFx.hasText(view, "movie-night") && E2eFx.hasText(view, "1 scene(s)")
            }

            val listReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(listReq, "scenes list request should have reached the backend")
            assertEquals("GET", listReq!!.method)
            assertTrue(listReq.path!!.contains("/api/v1/smarthome/scenes"), "path was ${listReq.path}")

            // --- Journey step 2: activate the scene from its card ---
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "applied": 2,
                          "results": [
                            {"device": {"id": "lamp-1"}, "action": "DIM"},
                            {"deviceId": "lock-1", "action": "LOCK"}
                          ]
                        }
                        """.trimIndent()
                    )
            )
            E2eFx.onFx { buttonNamed(view, "Activate").fire() }

            E2eFx.waitForFx(description = "activation status reflected") {
                E2eFx.hasText(view, "activated") && E2eFx.hasText(view, "Activated")
            }

            val activateReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(activateReq, "activate request should have reached the backend")
            assertEquals("POST", activateReq!!.method)
            assertTrue(
                activateReq.path!!.contains("/smarthome/scenes/movie-night/activate"),
                "path was ${activateReq.path}"
            )

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty payload renders the no-scenes placeholder`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { ScenesView(E2eFx.apiClientFor(server)) }

            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody("[]")
            )
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "empty-state placeholder shown") {
                E2eFx.hasText(view, "No scenes yet") && E2eFx.hasText(view, "0 scene(s)")
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
    fun `backend 500 on list surfaces an error placeholder and status`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { ScenesView(E2eFx.apiClientFor(server)) }

            server.enqueue(MockResponse().setResponseCode(500).setBody("kaboom"))
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "scenes load error rendered") {
                E2eFx.hasText(view, "Unable to load scenes") && E2eFx.hasText(view, "Unavailable")
            }

            val req = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(req, "scenes list request should still have been attempted")
            assertEquals("GET", req!!.method)
            assertTrue(req.path!!.contains("/smarthome/scenes"), "path was ${req.path}")

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }
}
