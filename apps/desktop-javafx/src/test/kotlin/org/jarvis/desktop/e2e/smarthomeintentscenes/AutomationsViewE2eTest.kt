package org.jarvis.desktop.e2e.smarthomeintentscenes

import javafx.scene.Node
import javafx.scene.control.Button
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.smarthome.AutomationsView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * True headless-UI E2E journeys for [AutomationsView] (Smart Home
 * "Automations" panel). Drives the real Refresh + per-rule Simulate (dry-run)
 * buttons against a [MockWebServer] and asserts both the rendered rule cards /
 * simulation result text and the backend requests.
 */
class AutomationsViewE2eTest {

    private fun buttonNamed(root: Node, text: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == text }

    private fun rulesBody(): String =
        """
        [
          {
            "id": "rule-1",
            "name": "Hall motion light",
            "triggerDeviceId": "hall_motion",
            "triggerEvent": "MOTION_DETECTED",
            "triggerThreshold": null,
            "actionDeviceId": "hall_light",
            "actionType": "TURN_ON",
            "actionPayload": null,
            "allowSensitiveActions": false,
            "enabled": true
          }
        ]
        """.trimIndent()

    @Test
    fun `list automation rules then dry-run simulate a triggered rule`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { AutomationsView(E2eFx.apiClientFor(server)) }

            // --- Journey step 1: load rules ---
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(rulesBody())
            )
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "rule card rendered") {
                E2eFx.hasText(view, "Hall motion light") && E2eFx.hasText(view, "1 rule(s)")
            }

            val listReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(listReq, "rules list request should have reached the backend")
            assertEquals("GET", listReq!!.method)
            assertTrue(
                listReq.path!!.contains("/api/v1/smarthome/automation/rules"),
                "path was ${listReq.path}"
            )

            // --- Journey step 2: simulate the rule (dry run) ---
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        [
                          {
                            "ruleId": "rule-1",
                            "ruleName": "Hall motion light",
                            "triggered": true,
                            "predictedAction": {
                              "deviceId": "hall_light",
                              "action": "TURN_ON",
                              "payload": null,
                              "deviceFound": true,
                              "actionSupported": true,
                              "needsConfirmation": false,
                              "wouldExecute": true,
                              "message": "Would turn on hall_light"
                            },
                            "message": "Rule triggered"
                          }
                        ]
                        """.trimIndent()
                    )
            )
            E2eFx.onFx { buttonNamed(view, "Simulate").fire() }

            E2eFx.waitForFx(description = "simulation result rendered") {
                E2eFx.hasText(view, "Rule triggered")
            }

            val simReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(simReq, "simulate request should have reached the backend")
            assertEquals("POST", simReq!!.method)
            assertTrue(
                simReq.path!!.contains("/smarthome/devices/hall_motion/automation/simulate"),
                "path was ${simReq.path}"
            )

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `simulate with no matching reading reports not-triggered`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { AutomationsView(E2eFx.apiClientFor(server)) }

            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(rulesBody())
            )
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }
            E2eFx.waitForFx(description = "rule card rendered") { E2eFx.hasText(view, "Hall motion light") }
            server.takeRequest(5, TimeUnit.SECONDS)

            // No simulation matched this rule -> "Not triggered" message.
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody("[]")
            )
            E2eFx.onFx { buttonNamed(view, "Simulate").fire() }

            E2eFx.waitForFx(description = "not-triggered result rendered") {
                E2eFx.hasText(view, "Not triggered")
            }

            val simReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(simReq, "simulate request should have reached the backend")
            assertEquals("POST", simReq!!.method)
            assertTrue(
                simReq.path!!.contains("/smarthome/devices/hall_motion/automation/simulate"),
                "path was ${simReq.path}"
            )

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `backend 500 on rules load surfaces an error placeholder and status`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { AutomationsView(E2eFx.apiClientFor(server)) }

            server.enqueue(MockResponse().setResponseCode(500).setBody("down"))
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "rules load error rendered") {
                E2eFx.hasText(view, "Unable to load automation rules") && E2eFx.hasText(view, "Unavailable")
            }

            val req = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(req, "rules request should still have been attempted")
            assertEquals("GET", req!!.method)
            assertTrue(req.path!!.contains("/smarthome/automation/rules"), "path was ${req.path}")

            E2eFx.onFx { view.shutdown() }
        } finally {
            server.shutdown()
        }
    }
}
