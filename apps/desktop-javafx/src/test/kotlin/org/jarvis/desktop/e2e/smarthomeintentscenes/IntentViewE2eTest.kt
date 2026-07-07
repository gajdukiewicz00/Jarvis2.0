package org.jarvis.desktop.e2e.smarthomeintentscenes

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.TextField
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.smarthome.IntentView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * True headless-UI E2E journeys for [IntentView] (Smart Home natural-language
 * intent box). Drives the real scene graph (TextField + Resolve/Execute
 * buttons) against a [MockWebServer] and asserts BOTH the visible widget tree
 * reacting AND the backend receiving the expected requests.
 */
class IntentViewE2eTest {

    private fun buttonNamed(root: Node, text: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == text }

    @Test
    fun `resolve a natural-language intent then execute the planned action`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { IntentView(E2eFx.apiClientFor(server)) }

            // --- Journey step 1: type an utterance and hit Resolve ---
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "utterance": "turn on the kitchen light",
                          "status": "RESOLVED",
                          "confidence": 0.95,
                          "action": "TURN_ON",
                          "payload": null,
                          "device": {
                            "id": "kitchen-light",
                            "displayName": "Kitchen Light",
                            "room": "kitchen",
                            "supportedActions": ["TURN_ON", "TURN_OFF"]
                          },
                          "candidates": [],
                          "message": "Ready to execute"
                        }
                        """.trimIndent()
                    )
            )
            E2eFx.onFx {
                E2eFx.find<TextField>(view)!!.text = "turn on the kitchen light"
                buttonNamed(view, "Resolve").fire()
            }

            // The plan renders AND the Execute button becomes enabled (isExecutable).
            E2eFx.waitForFx(description = "intent resolved + execute enabled") {
                E2eFx.hasText(view, "RESOLVED") && !buttonNamed(view, "Execute").isDisable
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Kitchen Light"), "device name should render in the plan")
                assertTrue(E2eFx.hasText(view, "TURN_ON"), "planned action should render")
            }

            val resolveReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(resolveReq, "resolve request should have reached the backend")
            assertEquals("POST", resolveReq!!.method)
            assertTrue(resolveReq.path!!.contains("/api/v1/smarthome/intent"), "path was ${resolveReq.path}")
            assertTrue(resolveReq.body.readUtf8().contains("kitchen"), "utterance should be posted")

            // --- Journey step 2: execute the resolved plan ---
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"success": true, "needsConfirmation": false, "action": "TURN_ON", "message": "ok"}""")
            )
            E2eFx.onFx { buttonNamed(view, "Execute").fire() }

            E2eFx.waitForFx(description = "action executed, status reflects success") {
                E2eFx.hasText(view, "Executed")
            }

            val execReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(execReq, "execute request should have reached the backend")
            assertEquals("POST", execReq!!.method)
            assertTrue(
                execReq.path!!.contains("/smarthome/devices/kitchen-light/action"),
                "path was ${execReq.path}"
            )
            assertTrue(execReq.path!!.contains("confirm=false"), "first execute is unconfirmed; path ${execReq.path}")

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `backend 500 on resolve surfaces an error in the plan area and status pill`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { IntentView(E2eFx.apiClientFor(server)) }

            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            E2eFx.onFx {
                E2eFx.find<TextField>(view)!!.text = "do something impossible"
                buttonNamed(view, "Resolve").fire()
            }

            E2eFx.waitForFx(description = "resolve error rendered") {
                E2eFx.hasText(view, "Unable to resolve intent") && E2eFx.hasText(view, "Unavailable")
            }

            val req = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(req, "resolve request should still have been attempted")
            assertEquals("POST", req!!.method)
            assertTrue(req.path!!.contains("/smarthome/intent"), "path was ${req.path}")

            // Execute must stay disabled because there is no executable plan.
            E2eFx.onFx { assertTrue(buttonNamed(view, "Execute").isDisable, "Execute stays disabled after error") }

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }
}
