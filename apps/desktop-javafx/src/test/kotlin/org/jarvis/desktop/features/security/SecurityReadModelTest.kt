package org.jarvis.desktop.features.security

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

class SecurityReadModelTest {

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

    private fun modelFor(server: MockWebServer) = SecurityReadModel(ApiClient(configFor(server)))

    @Test
    fun `status parses enabled privacy snapshot`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "privacyEnabled": true, "detail": "Privacy is active" }""")
        )

        try {
            server.start()
            val snapshot = modelFor(server).status()
            assertTrue(snapshot.enabled)
            assertEquals("Privacy is active", snapshot.detail)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `status defaults detail message when missing`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "enabled": false }""")
        )

        try {
            server.start()
            val snapshot = modelFor(server).status()
            assertFalse(snapshot.enabled)
            assertEquals("Privacy mode is OFF.", snapshot.detail)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `status falls back to raw body when response is not JSON`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("not json"))

        try {
            server.start()
            val snapshot = modelFor(server).status()
            assertFalse(snapshot.enabled)
            assertEquals("not json", snapshot.detail)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `enablePrivacy posts to the on endpoint and parses response`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "active": true, "message": "Privacy enabled" }""")
        )

        try {
            server.start()
            val snapshot = modelFor(server).enablePrivacy()
            val request = server.takeRequest()

            assertEquals("/api/v1/security/auth/privacy/on", request.path)
            assertTrue(snapshot.enabled)
            assertEquals("Privacy enabled", snapshot.detail)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `disablePrivacy posts to the off endpoint`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "privacy": false, "reason": "Privacy disabled" }""")
        )

        try {
            server.start()
            val snapshot = modelFor(server).disablePrivacy()
            val request = server.takeRequest()

            assertEquals("/api/v1/security/auth/privacy/off", request.path)
            assertFalse(snapshot.enabled)
            assertEquals("Privacy disabled", snapshot.detail)
        } finally {
            server.shutdown()
        }
    }
}
