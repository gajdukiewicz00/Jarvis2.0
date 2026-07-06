package org.jarvis.desktop.features.smarthome

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class IntentReadModelTest {

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

    private fun modelFor(server: MockWebServer) = IntentReadModel(ApiClient(configFor(server)))

    @Test
    fun `resolve parses a RESOLVED plan with a matched device`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "utterance": "turn on the kitchen light",
                      "status": "RESOLVED",
                      "confidence": 0.92,
                      "action": "TURN_ON",
                      "payload": null,
                      "device": {"id": "kitchen-light", "displayName": "Kitchen Light", "room": "Kitchen", "supportedActions": ["TURN_ON", "TURN_OFF"]},
                      "candidates": [],
                      "message": null
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val resolution = modelFor(server).resolve("turn on the kitchen light")

            assertEquals("RESOLVED", resolution.status)
            assertEquals(0.92, resolution.confidence)
            assertEquals("TURN_ON", resolution.action)
            assertEquals("kitchen-light", resolution.device?.id)
            assertEquals("Kitchen", resolution.device?.room)
            assertTrue(resolution.isExecutable)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/smarthome/intent"))
            assertTrue(request.body.readUtf8().contains("turn on the kitchen light"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `resolve parses AMBIGUOUS candidates and is not executable`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "utterance": "turn on the light",
                      "status": "AMBIGUOUS",
                      "confidence": 0.4,
                      "action": "TURN_ON",
                      "device": null,
                      "candidates": [
                        {"id": "kitchen-light", "displayName": "Kitchen Light", "room": "Kitchen", "supportedActions": ["TURN_ON"]},
                        {"id": "hall-light", "displayName": "Hall Light", "room": "Hall", "supportedActions": ["TURN_ON"]}
                      ],
                      "message": "multiple lights matched"
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val resolution = modelFor(server).resolve("turn on the light")

            assertEquals("AMBIGUOUS", resolution.status)
            assertEquals(2, resolution.candidates.size)
            assertFalse(resolution.isExecutable)
            assertEquals("multiple lights matched", resolution.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `resolve treats UNKNOWN status as not executable`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"utterance": "do something", "status": "UNKNOWN", "confidence": 0.0, "candidates": []}""")
        )

        try {
            server.start()
            val resolution = modelFor(server).resolve("do something")

            assertEquals("UNKNOWN", resolution.status)
            assertFalse(resolution.isExecutable)
        } finally {
            server.shutdown()
        }
    }
}
