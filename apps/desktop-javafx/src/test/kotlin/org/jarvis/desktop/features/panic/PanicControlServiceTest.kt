package org.jarvis.desktop.features.panic

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

class PanicControlServiceTest {

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

    private fun serviceFor(server: MockWebServer) = PanicControlService(ApiClient(configFor(server)))

    @Test
    fun `status parses an engaged panic snapshot`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "engaged": true, "actor": "operator", "reason": "drill", "sinceMillis": "1" }""")
        )

        try {
            server.start()
            val snapshot = serviceFor(server).status()

            assertTrue(snapshot.engaged)
            assertEquals("operator", snapshot.actor)
            assertEquals("drill", snapshot.reason)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `status falls back to the raw body when the response is not JSON`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("not json"))

        try {
            server.start()
            val snapshot = serviceFor(server).status()

            assertFalse(snapshot.engaged)
            assertEquals("not json", snapshot.detail)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `engage posts to the panic endpoint with actor and reason`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "engaged": true, "actor": "desktop-shell", "reason": "test reason" }""")
        )

        try {
            server.start()
            val snapshot = serviceFor(server).engage("test reason")
            val request = server.takeRequest()

            assertEquals("/api/v1/agent/panic", request.path)
            assertEquals("POST", request.method)
            assertTrue(request.body.readUtf8().contains("test reason"))
            assertTrue(snapshot.engaged)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `clear posts to the panic clear endpoint`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "engaged": false, "actor": "desktop-shell" }""")
        )

        try {
            server.start()
            val snapshot = serviceFor(server).clear()
            val request = server.takeRequest()

            assertEquals("/api/v1/agent/panic/clear", request.path)
            assertFalse(snapshot.engaged)
        } finally {
            server.shutdown()
        }
    }
}
