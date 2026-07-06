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

class SmartHomeActionReadModelTest {

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

    private fun modelFor(server: MockWebServer) = SmartHomeActionReadModel(ApiClient(configFor(server)))

    @Test
    fun `execute defaults confirm to false in the query string`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success": true, "action": "TOGGLE", "needsConfirmation": false}""")
        )

        try {
            server.start()
            val outcome = modelFor(server).execute("light-1", "TOGGLE", null)

            assertTrue(outcome.success)
            assertFalse(outcome.needsConfirmation)
            assertEquals("TOGGLE", outcome.action)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/smarthome/devices/light-1/action"))
            assertTrue(request.path!!.contains("confirm=false"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `execute reports needsConfirmation for an unconfirmed security-critical action`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success": false, "action": "LOCK", "needsConfirmation": true, "message": "confirmation required"}""")
        )

        try {
            server.start()
            val outcome = modelFor(server).execute("lock-1", "LOCK", null, confirm = false)

            assertFalse(outcome.success)
            assertTrue(outcome.needsConfirmation)
            assertEquals("confirmation required", outcome.message)

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("confirm=false"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `execute sends confirm=true and the payload when supplied`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success": true, "action": "SET_COLOR", "needsConfirmation": false}""")
        )

        try {
            server.start()
            modelFor(server).execute("light-1", "SET_COLOR", "warm_white", confirm = true)

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("confirm=true"))
            assertTrue(request.body.readUtf8().contains("warm_white"))
        } finally {
            server.shutdown()
        }
    }
}
