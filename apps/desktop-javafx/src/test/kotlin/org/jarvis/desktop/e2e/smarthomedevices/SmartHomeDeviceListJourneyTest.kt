package org.jarvis.desktop.e2e.smarthomedevices

import javafx.scene.Node
import javafx.scene.control.Button
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.ui.tabs.DevicesTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * True-UI end-to-end journeys for the Smart Home device list.
 *
 * These drive the REAL production device-list widget — [DevicesTab] (the same
 * component [org.jarvis.desktop.features.smarthome.SmartHomeView] hosts) —
 * pointed at a [MockWebServer]: each test constructs it on the FX thread,
 * drives an actual action button, then asserts BOTH the visible scene graph
 * reacted AND the gateway received the expected request.
 *
 * DevicesTab wires:
 *  - list   -> GET  /api/v1/smarthome/devices                 (auto on construction, + refresh())
 *  - action -> POST /api/v1/smarthome/devices/{id}/action?confirm=
 *
 * (The full SmartHomeView composite additionally embeds IntentView/ScenesView,
 * which are covered by their own dedicated E2E classes.)
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

    private fun json(body: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /**
     * Build the REAL device-list widget on the FX thread and return its content
     * root. Construction auto-loads the device list (DevicesTab.init -> loadDevices
     * on a background thread). The content is a ScrollPane; E2eFx's traversal
     * descends into a ScrollPane's content even while unskinned, so the device
     * cards/status/buttons are reachable without attaching a Scene.
     */
    private fun buildDeviceList(server: MockWebServer): Node =
        E2eFx.onFx { requireNotNull(DevicesTab(E2eFx.apiClientFor(server)).tab.content) }

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
    private fun fireActionButton(root: Node, label: String) {
        E2eFx.onFx {
            val button = E2eFx.findAll<Button>(root)
                .firstOrNull { it.text?.equals(label, ignoreCase = true) == true }
                ?: error("No action button labelled \"$label\" in the device list")
            button.fire()
        }
    }

    @Test
    fun `device list loads and renders a card per device on construction`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path.orEmpty().contains("/smarthome/devices")) json("[$lightOff]")
                else MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val root = buildDeviceList(server)

            E2eFx.waitForFx(description = "device card + loaded status render") {
                E2eFx.hasText(root, "Kitchen Light") && E2eFx.hasText(root, "Loaded 1 device")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "Toggle"), "TOGGLE action button should render")
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
                    path.contains("/smarthome/devices") ->
                        json("[${if (deviceGets.getAndIncrement() == 0) lightOff else lightOn}]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val root = buildDeviceList(server)
            E2eFx.waitForFx(description = "initial off-state device renders") {
                E2eFx.hasText(root, "Kitchen Light") && E2eFx.hasText(root, "Toggle")
            }

            fireActionButton(root, "Toggle")

            E2eFx.waitForFx(description = "reloaded device shows on-state") {
                E2eFx.hasText(root, "80%") || E2eFx.hasText(root, "On")
            }

            val action = awaitRequest(server) {
                it.method == "POST" && it.path?.contains("/action") == true
            }
            assertTrue(action.path!!.contains("/smarthome/devices/kitchen-light/action"), "targets the light")
            assertTrue(action.body.readUtf8().contains("TOGGLE"), "TOGGLE action in body")
        } finally {
            server.shutdown()
        }
    }

    // NOTE: The security-critical confirmation gate (firing a LOCK on a lock/door/garage
    // device) is intentionally NOT covered here. On needsConfirmation, DevicesTab opens a
    // modal `Dialog.showAndWait()` synchronously on the FX thread; under Monocle headless
    // that nested event loop cannot be dismissed cleanly (no Robot/real Stage), leaving the
    // FX thread stuck and poisoning sibling tests. The confirmation-gate LOGIC is covered at
    // the model/handler level elsewhere; the modal itself is not headless-drivable.

    @Test
    fun `device list surfaces a backend 500 as a visible error`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path.orEmpty().contains("/smarthome/devices")) MockResponse().setResponseCode(500)
                else MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val root = buildDeviceList(server)

            E2eFx.waitForFx(description = "500 surfaces as a visible error") {
                E2eFx.hasText(root, "Unable to load devices") || E2eFx.hasText(root, "Server error (500)")
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
