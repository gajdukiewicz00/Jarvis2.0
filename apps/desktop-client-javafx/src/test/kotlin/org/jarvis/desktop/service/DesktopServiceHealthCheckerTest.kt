package org.jarvis.desktop.service

import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.Locale

class DesktopServiceHealthCheckerTest {

    @Test
    fun `service checks use resolved endpoints and websocket auth headers`() {
        TokenManager.clearTokens()
        TokenManager.saveTokens(
            "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ1c2VyLTEyMyJ9.",
            "refresh-token",
            "alice",
            "USER"
        )

        try {
            val gatewayUris = mutableListOf<URI>()
            val apiCalls = mutableListOf<Pair<String, String>>()
            val webSocketCalls = mutableListOf<Pair<String, Map<String, String>>>()
            val checker = DesktopServiceHealthChecker(
                configProvider = { remoteConfig() },
                apiProbe = { name, endpoint ->
                    apiCalls += name to endpoint
                    DesktopServiceHealthChecker.ProbeOutcome(DesktopServiceHealthChecker.Status.ONLINE, "OK")
                },
                gatewayProbe = { uri ->
                    gatewayUris += uri
                    DesktopServiceHealthChecker.ProbeOutcome(DesktopServiceHealthChecker.Status.ONLINE, "UP")
                },
                webSocketProbe = { url, headers ->
                    webSocketCalls += url to headers
                    DesktopServiceHealthChecker.ProbeOutcome(DesktopServiceHealthChecker.Status.ONLINE, "Connected")
                }
            )

            val results = checker.checkAll()

            assertEquals(listOf(URI.create("https://api.jarvis.local/actuator/health")), gatewayUris)
            assertEquals(
                listOf(
                    "Auth Context" to "/security/auth/me",
                    "Smart Home API" to "/smarthome/devices",
                    "Life Tracker API" to "/life/finance/expenses",
                    "Analytics API" to "/analytics/expenses/by-month"
                ),
                apiCalls
            )
            assertEquals(
                listOf(
                    "wss://api.jarvis.local/ws/voice",
                    "wss://api.jarvis.local/ws/pc-control"
                ),
                webSocketCalls.map { it.first }
            )
            assertEquals("Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJ1c2VyLTEyMyJ9.", webSocketCalls.first().second["Authorization"])
            assertEquals("alice", webSocketCalls.first().second["X-Username"])
            assertEquals("USER", webSocketCalls.first().second["X-User-Roles"])
            assertEquals(listOf(DesktopServiceHealthChecker.Status.ONLINE), results.map { it.status }.distinct())
        } finally {
            TokenManager.clearTokens()
        }
    }

    private fun remoteConfig(): ResolvedDesktopConfig {
        return ResolvedDesktopConfig(
            apiGatewayBaseUrl = "https://api.jarvis.local",
            apiBaseUrl = "https://api.jarvis.local/api/v1",
            voiceWebSocketUrl = "wss://api.jarvis.local/ws/voice",
            pcControlWebSocketUrl = "wss://api.jarvis.local/ws/pc-control",
            locale = Locale.ENGLISH,
            voiceLanguage = "en-US",
            apiGatewaySource = ConfigSource.ENV_JARVIS_API_BASE_URL,
            apiGatewayReason = "test config",
            usesManualEndpointOverride = false
        )
    }
}
