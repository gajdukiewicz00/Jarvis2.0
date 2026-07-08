package org.jarvis.desktop.features.smarthome

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class AutomationsReadModelTest {

    private fun configFor(server: MockWebServer): () -> ResolvedDesktopConfig {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = baseUrl,
                apiBaseUrl = "$baseUrl/api/v1",
                voiceWebSocketUrl = "$baseUrl/ws/voice",
                pcControlWebSocketUrl = "$baseUrl/ws/pc-control",
                locale = Locale.ENGLISH,
                voiceLanguage = "en-US",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                apiGatewayReason = "test",
                usesManualEndpointOverride = true
            )
        }
    }

    private fun modelFor(server: MockWebServer) = AutomationsReadModel(ApiClient(configFor(server)))

    @Test
    fun `loadRules parses a full rule with every field populated`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {
                        "id": "rule-1", "name": "Motion turns on hall light",
                        "triggerDeviceId": "hall_motion", "triggerEvent": "MOTION_DETECTED", "triggerThreshold": 0.8,
                        "actionDeviceId": "hall_light", "actionType": "TURN_ON", "actionPayload": "warm_white",
                        "allowSensitiveActions": true, "enabled": true
                      }
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val rules = modelFor(server).loadRules()

            assertEquals(1, rules.size)
            val rule = rules[0]
            assertEquals("rule-1", rule.id)
            assertEquals("Motion turns on hall light", rule.name)
            assertEquals("hall_motion", rule.triggerDeviceId)
            assertEquals("MOTION_DETECTED", rule.triggerEvent)
            assertEquals(0.8, rule.triggerThreshold!!, 0.0001)
            assertEquals("hall_light", rule.actionDeviceId)
            assertEquals("TURN_ON", rule.actionType)
            assertEquals("warm_white", rule.actionPayload)
            assertTrue(rule.allowSensitiveActions)
            assertTrue(rule.enabled)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.path!!.contains("/smarthome/automation/rules"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadRules defaults missing optional fields`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""[{"id": "rule-2", "triggerDeviceId": "front_door"}]""")
        )

        try {
            server.start()
            val rule = modelFor(server).loadRules().single()

            assertEquals("rule-2", rule.id)
            assertEquals("", rule.name)
            assertEquals("front_door", rule.triggerDeviceId)
            assertEquals("", rule.triggerEvent)
            assertNull(rule.triggerThreshold)
            assertEquals("", rule.actionDeviceId)
            assertEquals("", rule.actionType)
            assertNull(rule.actionPayload)
            assertFalse(rule.allowSensitiveActions)
            assertFalse(rule.enabled)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadRules returns an empty list when the payload is not an array`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"error": "unexpected"}""")
        )

        try {
            server.start()
            assertTrue(modelFor(server).loadRules().isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadRules propagates a server error instead of silently degrading`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            server.start()
            val ex = assertThrows(Exception::class.java) { modelFor(server).loadRules() }
            assertTrue(ex.message!!.contains("Server error"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `simulate parses a triggered rule with its predicted action`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {
                        "ruleId": "rule-1", "ruleName": "Motion turns on hall light", "triggered": true,
                        "predictedAction": {
                          "deviceId": "hall_light", "action": "TURN_ON", "payload": "warm_white",
                          "deviceFound": true, "actionSupported": true, "needsConfirmation": false,
                          "wouldExecute": true, "message": null
                        },
                        "message": "Trigger condition met"
                      }
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val simulations = modelFor(server).simulate("hall_motion")

            assertEquals(1, simulations.size)
            val sim = simulations[0]
            assertEquals("rule-1", sim.ruleId)
            assertTrue(sim.triggered)
            assertEquals("Trigger condition met", sim.message)

            val predicted = sim.predictedAction
            assertEquals("hall_light", predicted?.deviceId)
            assertEquals("TURN_ON", predicted?.action)
            assertTrue(predicted!!.deviceFound)
            assertTrue(predicted.actionSupported)
            assertFalse(predicted.needsConfirmation)
            assertTrue(predicted.wouldExecute)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/smarthome/devices/hall_motion/automation/simulate"))
            assertEquals("{}", request.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `simulate leaves predictedAction null when the rule did not trigger`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """[{"ruleId": "rule-3", "ruleName": "Unrelated rule", "triggered": false,
                        "message": "Trigger device does not match"}]"""
                )
        )

        try {
            server.start()
            val sim = modelFor(server).simulate("front_door").single()

            assertFalse(sim.triggered)
            assertNull(sim.predictedAction)
            assertEquals("Trigger device does not match", sim.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `simulate returns an empty list when the payload is not an array`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"error": "no device"}""")
        )

        try {
            server.start()
            assertTrue(modelFor(server).simulate("unknown-device").isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `simulate URL-encodes a device id containing spaces`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""[]"""))

        try {
            server.start()
            modelFor(server).simulate("hall light")

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/smarthome/devices/hall+light/automation/simulate"))
        } finally {
            server.shutdown()
        }
    }
}
