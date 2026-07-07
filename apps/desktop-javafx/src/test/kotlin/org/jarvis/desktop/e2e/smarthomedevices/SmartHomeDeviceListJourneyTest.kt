package org.jarvis.desktop.e2e.smarthomedevices

import javafx.scene.control.Button
import javafx.stage.Window
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.smarthome.SmartHomeView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * True-UI end-to-end journeys for the Smart Home device list (the legacy
 * [org.jarvis.desktop.ui.tabs.DevicesTab] hosted inside [SmartHomeView]).
 *
 * Each test constructs the REAL [SmartHomeView] on the FX thread pointed at a
 * [MockWebServer], drives an actual control, then asserts BOTH the visible
 * scene graph reacted AND the gateway received the expected request.
 *
 * [SmartHomeView] wires:
 *  - list    -> GET  /api/v1/smarthome/devices              (auto on construction + onRouteActivated)
 *  - action  -> POST /api/v1/smarthome/devices/{id}/action?confirm=
 *  - scenes  -> GET  /api/v1/smarthome/scenes               (onRouteActivated)
 */
class SmartHomeDeviceListJourneyTest {

    private val lightOff = """
        {
          "id": "kitchen-light",
          "displayName": "Kitchen Light",
          "room": "Kitchen",
          "type": "LIGHT",
          "supportedActions": ["TOGGLE", "TURN_ON", "TURN_OFF"],
          "state": {"power": false, "brightness": 0},
          "provider": "hue",
          "updatedAt": "2026-07-06T10:00:00Z"
        }
    """.trimIndent()

    private val lightOn = """
        {
          "id": "kitchen-light",
          "displayName": "Kitchen Light",
          "room": "Kitchen",
          "type": "LIGHT",
          "supportedActions": ["TOGGLE", "TURN_ON", "TURN_OFF"],
          "state": {"power": true, "brightness": 80, "color": "warm_white"},
          "provider": "hue",
          "updatedAt": "2026-07-06T10:05:00Z"
        }
    """.trimIndent()

    private val lockDevice = """
        {
          "id": "front-door",
          "displayName": "Front Door Lock",
          "room": "Entrance",
          "type": "LOCK",
          "supportedActions": ["LOCK", "UNLOCK"],
          "state": {"locked": false},
          "provider": "august",
          "updatedAt": "2026-07-06T10:00:00Z"
        }
    """.trimIndent()

    private fun json(body: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** Drain the recorded-request queue until one matches [predicate] or the timeout elapses. */
    private fun awaitRequest(
        server: MockWebServer,
        timeoutMs: Long = 5_000,
        predicate: (RecordedRequest) -> Boolean
    ): RecordedRequest {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val req = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(req)) return req
        }
        throw AssertionError("Timed out waiting for a matching backend request")
    }

    /** Fire a device-card action button whose label equals [label] (case-insensitive). */
    private fun fireActionButton(view: SmartHomeView, label: String) {
        E2eFx.onFx {
            val button = E2eFx.findAll<Button>(view)
                .firstOrNull { it.text?.equals(label, ignoreCase = true) == true }
                ?: error("No action button labelled \"$label\" in the device list")
            button.fire()
        }
    }

    @Test
    fun `smart home view loads and renders the device list on construction`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/smarthome/scenes") -> json("[]")
                    path.contains("/smarthome/devices") -> json("[$lightOff]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val view = E2eFx.onFx { SmartHomeView(E2eFx.apiClientFor(server)) }

            // The device card and the success status must both surface.
            E2eFx.waitForFx(description = "device card + loaded status render") {
                E2eFx.hasText(view, "Kitchen Light") && E2eFx.hasText(view, "Loaded 1 device")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Off"), "off-state summary should render")
                assertTrue(E2eFx.hasText(view, "Toggle"), "TOGGLE action button should render")
            }

            val req = awaitRequest(server) {
                it.method == "GET" && it.path?.contains("/smarthome/devices") == true
            }
            assertEquals("GET", req.method)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `executing a device action posts to the backend and the reloaded list reflects new state`() {
        val deviceGets = AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path.contains("/action") ->
                        json("""{"success": true, "needsConfirmation": false, "action": "TOGGLE"}""")
                    path.contains("/smarthome/scenes") -> json("[]")
                    path.contains("/smarthome/devices") -> {
                        // First load = off; after a successful action the reload = on.
                        val body = if (deviceGets.getAndIncrement() == 0) lightOff else lightOn
                        json("[$body]")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val view = E2eFx.onFx { SmartHomeView(E2eFx.apiClientFor(server)) }
            E2eFx.waitForFx(description = "initial off-state device renders") {
                E2eFx.hasText(view, "Kitchen Light") && E2eFx.hasText(view, "Off")
            }

            fireActionButton(view, "Toggle")

            // Durable UI reaction: the reloaded card shows the new on-state
            // (brightness 80% is unique to the post-action reload; initial load is 0%).
            E2eFx.waitForFx(description = "reloaded device shows on-state") {
                E2eFx.hasText(view, "80%")
            }

            val action = awaitRequest(server) {
                it.method == "POST" && it.path?.contains("/action") == true
            }
            assertTrue(action.path!!.contains("/smarthome/devices/kitchen-light/action"), "posts to the device action endpoint")
            assertTrue(action.path!!.contains("confirm=false"), "first attempt is unconfirmed")
            val body = action.body.readUtf8()
            assertTrue(body.contains("\"action\":\"TOGGLE\""), "body carries the TOGGLE action, was: $body")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a LOCK action is gated behind confirmation and the status reflects the gate`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path.contains("/action") ->
                        // Backend gate: security-critical device -> 200 with needsConfirmation.
                        json("""{"success": false, "needsConfirmation": true, "action": "LOCK"}""")
                    path.contains("/smarthome/scenes") -> json("[]")
                    path.contains("/smarthome/devices") -> json("[$lockDevice]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val view = E2eFx.onFx { SmartHomeView(E2eFx.apiClientFor(server)) }
            E2eFx.waitForFx(description = "lock device renders") {
                E2eFx.hasText(view, "Front Door Lock") && E2eFx.hasText(view, "Lock")
            }

            fireActionButton(view, "Lock")

            // The status label reflects the confirmation gate. This is set on the FX
            // thread immediately before the modal confirm dialog opens; showAndWait
            // pumps a nested event loop so the polling helper still observes it.
            E2eFx.waitForFx(description = "status reflects confirmation gate") {
                E2eFx.hasText(view, "needs confirmation")
            }

            val action = awaitRequest(server) {
                it.method == "POST" && it.path?.contains("/action") == true
            }
            assertTrue(action.path!!.contains("/smarthome/devices/front-door/action"), "targets the lock")
            assertTrue(action.path!!.contains("confirm=false"), "unconfirmed request sent first")
            assertTrue(action.body.readUtf8().contains("\"action\":\"LOCK\""), "LOCK action in body")
        } finally {
            // Release any confirm dialog left blocking on a nested event loop so the
            // FX thread is clean for subsequent tests.
            runCatching {
                E2eFx.onFx {
                    Window.getWindows().toList()
                        .filter { it.isShowing }
                        .forEach { it.hide() }
                }
            }
            server.shutdown()
        }
    }

    @Test
    fun `device list surfaces a backend 500 as a visible error`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/smarthome/scenes") -> json("[]")
                    path.contains("/smarthome/devices") -> MockResponse().setResponseCode(500)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val view = E2eFx.onFx { SmartHomeView(E2eFx.apiClientFor(server)) }

            E2eFx.waitForFx(description = "500 surfaces as a visible error") {
                E2eFx.hasText(view, "Unable to load devices") || E2eFx.hasText(view, "Server error (500)")
            }
            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view, "Unable to load devices") || E2eFx.hasText(view, "Server error (500)"),
                    "error status/body should be visible, was: ${E2eFx.visibleText(view)}"
                )
            }

            val req = awaitRequest(server) {
                it.method == "GET" && it.path?.contains("/smarthome/devices") == true
            }
            assertEquals("GET", req.method)
        } finally {
            server.shutdown()
        }
    }
}
