package org.jarvis.desktop.e2e.smarthomedevices

import javafx.scene.control.Button
import javafx.scene.control.TextField
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.smarthome.DeviceStateHistoryView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * True-UI end-to-end journeys for the Smart Home "Device state history" panel.
 *
 * [DeviceStateHistoryView] does NOT auto-load — the owner types a device id and
 * loads history explicitly. Each test constructs the REAL view on the FX
 * thread, sets the device-id field, fires the load control, then asserts BOTH
 * the visible scene graph reacted AND the gateway received the expected
 * `GET /api/v1/smarthome/devices/{id}/state-history?limit=` request.
 */
class DeviceStateHistoryJourneyTest {

    private fun json(body: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun deviceIdField(view: DeviceStateHistoryView): TextField =
        E2eFx.findAll<TextField>(view).firstOrNull { it.promptText?.contains("Device id") == true }
            ?: error("Device id field not found")

    private fun loadButton(view: DeviceStateHistoryView): Button =
        E2eFx.findAll<Button>(view).firstOrNull { it.text == "Load history" }
            ?: error("Load history button not found")

    @Test
    fun `loading history renders entry cards and calls the state-history endpoint`() {
        val server = MockWebServer()
        server.enqueue(
            json(
                """
                [
                  {
                    "deviceId": "kitchen-light",
                    "action": "TURN_ON",
                    "payload": "warm_white",
                    "stateJson": "{\"power\":true,\"brightness\":80}",
                    "success": true,
                    "recordedAt": "2026-07-06T09:15:00Z"
                  }
                ]
                """.trimIndent()
            )
        )
        server.start()
        try {
            val view = E2eFx.onFx { DeviceStateHistoryView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx {
                deviceIdField(view).text = "kitchen-light"
                loadButton(view).fire()
            }

            E2eFx.waitForFx(description = "history entry card renders") {
                E2eFx.hasText(view, "TURN_ON") &&
                    E2eFx.hasText(view, "Success") &&
                    E2eFx.hasText(view, "1 entr")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "warm_white"), "payload should render, was: ${E2eFx.visibleText(view)}")
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(
                req.path!!.contains("/smarthome/devices/kitchen-light/state-history"),
                "targets the device state-history endpoint, was: ${req.path}"
            )
            assertTrue(req.path!!.contains("limit="), "carries a limit query param, was: ${req.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an empty history payload shows the no-history placeholder`() {
        val server = MockWebServer()
        server.enqueue(json("[]"))
        server.start()
        try {
            val view = E2eFx.onFx { DeviceStateHistoryView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx {
                deviceIdField(view).text = "spare-plug"
                loadButton(view).fire()
            }

            E2eFx.waitForFx(description = "empty-history placeholder renders") {
                E2eFx.hasText(view, "No recorded state history") && E2eFx.hasText(view, "0 entr")
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(req.path!!.contains("/smarthome/devices/spare-plug/state-history"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loading with a blank device id is gated client-side and issues no request`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { DeviceStateHistoryView(E2eFx.apiClientFor(server)) }
            // Leave the device-id field blank and fire load.
            E2eFx.onFx { loadButton(view).fire() }

            E2eFx.waitForFx(description = "blank id shows an input-needed message") {
                E2eFx.hasText(view, "device id is required") || E2eFx.hasText(view, "Input needed")
            }
            // No backend call must have been made for an empty device id.
            assertEquals(0, server.requestCount, "blank device id must not hit the backend")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a backend 500 surfaces an unavailable status and error placeholder`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        try {
            val view = E2eFx.onFx { DeviceStateHistoryView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx {
                deviceIdField(view).text = "garage-door"
                loadButton(view).fire()
            }

            E2eFx.waitForFx(description = "500 surfaces as unavailable + error placeholder") {
                E2eFx.hasText(view, "Unable to load history") &&
                    (E2eFx.hasText(view, "Unavailable") || E2eFx.hasText(view, "Server error (500)"))
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(req.path!!.contains("/smarthome/devices/garage-door/state-history"))
        } finally {
            server.shutdown()
        }
    }
}
