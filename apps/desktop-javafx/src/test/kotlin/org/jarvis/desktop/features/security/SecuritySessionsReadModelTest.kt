package org.jarvis.desktop.features.security

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class SecuritySessionsReadModelTest {

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

    private fun modelFor(server: MockWebServer) = SecuritySessionsReadModel(ApiClient(configFor(server)))

    @Test
    fun `listAudit parses recent audit events`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"eventType": "TOKEN_REVOKED", "userId": 7, "tokenReference": "jti-1", "occurredAt": "2026-01-01T10:00:00Z", "reason": "compromised"},
                      {"eventType": "REFRESH_ROTATED", "userId": null, "tokenReference": "jti-2", "occurredAt": "2026-01-02T10:00:00Z", "reason": null}
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val events = modelFor(server).listAudit(limit = 10)

            assertEquals(2, events.size)
            assertEquals("TOKEN_REVOKED", events[0].eventType)
            assertEquals("7", events[0].userId)
            assertEquals("compromised", events[0].reason)
            assertEquals("REFRESH_ROTATED", events[1].eventType)
            assertEquals(null, events[1].userId)
            assertEquals(null, events[1].reason)

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/security/auth/audit"))
            assertTrue(request.path!!.contains("limit=10"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `revokeToken posts token and reason and parses the result`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"revoked": true, "jti": "jti-9", "tokenType": "ACCESS"}""")
        )

        try {
            server.start()
            val result = modelFor(server).revokeToken("abc.def.ghi", "user requested")

            assertTrue(result.revoked)
            assertEquals("jti-9", result.jti)
            assertEquals("ACCESS", result.tokenType)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/security/auth/revoke"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("abc.def.ghi"))
            assertTrue(body.contains("user requested"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `revokeAllForUser posts to the revoke-all path with an optional reason query param`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"userId": 42, "revokedRefreshTokens": 3}""")
        )

        try {
            server.start()
            val result = modelFor(server).revokeAllForUser("42", "device lost")

            assertEquals("42", result.userId)
            assertEquals(3, result.revokedRefreshTokens)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/security/auth/revoke-all/42"))
            assertTrue(request.path!!.contains("reason="))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `revokeCurrentSession posts the refresh token and parses jti pair`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"revoked": true, "accessJti": "jti-access", "refreshJti": "jti-refresh"}""")
        )

        try {
            server.start()
            val result = modelFor(server).revokeCurrentSession("refresh.token.value")

            assertTrue(result.revoked)
            assertEquals("jti-access", result.accessJti)
            assertEquals("jti-refresh", result.refreshJti)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/security/auth/revoke-current"))
            assertTrue(request.body.readUtf8().contains("refresh.token.value"))
        } finally {
            server.shutdown()
        }
    }
}
