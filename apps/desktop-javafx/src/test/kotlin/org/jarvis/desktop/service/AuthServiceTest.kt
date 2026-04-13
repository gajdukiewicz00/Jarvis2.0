package org.jarvis.desktop.service

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Locale

class AuthServiceTest {

    @Test
    fun `refreshTokens serializes RefreshRequest as JSON`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "accessToken": "access-2",
                      "refreshToken": "refresh-2",
                      "expiresIn": 3600,
                      "username": "alice",
                      "role": "USER"
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val baseUrl = server.url("/").toString().removeSuffix("/")
            val authService = AuthService {
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

            val response = authService.refreshTokens("refresh-1")
            val request = server.takeRequest()

            assertEquals("/auth/refresh", request.path)
            assertEquals("application/json", request.getHeader("Content-Type"))
            assertEquals("""{"refreshToken":"refresh-1"}""", request.body.readUtf8())
            assertEquals("access-2", response.accessToken)
            assertEquals("refresh-2", response.refreshToken)
            assertEquals("alice", response.username)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `logout posts refresh token to auth logout endpoint`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))

        try {
            server.start()
            val baseUrl = server.url("/").toString().removeSuffix("/")
            TokenManager.saveTokens("access-1", "refresh-logout", "alice", "USER")
            val authService = AuthService {
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

            authService.logout()
            val request = server.takeRequest()

            assertEquals("/auth/logout", request.path)
            assertEquals("POST", request.method)
            assertEquals("""{"refreshToken":"refresh-logout"}""", request.body.readUtf8())
            assertNull(TokenManager.getAccessToken())
            assertNull(TokenManager.getRefreshToken())
        } finally {
            TokenManager.clearTokens()
            server.shutdown()
        }
    }
}
