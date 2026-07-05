package org.jarvis.desktop.features.proactive

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class ProactiveReadModelTest {

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

    private fun modelFor(server: MockWebServer) = ProactiveReadModel(ApiClient(configFor(server)))

    @Test
    fun `recentObservations parses first successful candidate endpoint`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    { "observations": [ { "title": "Reminder", "detail": "Take a break", "timestamp": "2026-07-04T10:00:00Z" } ] }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val result = modelFor(server).recentObservations()
            val request = server.takeRequest()

            assertEquals("/api/v1/proactive/observations", request.path)
            val available = assertInstanceOf(ProactiveReadModel.Result.Available::class.java, result)
            assertEquals(1, available.observations.size)
            assertEquals("Reminder", available.observations.first().title)
            assertEquals("Take a break", available.observations.first().detail)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `recentObservations falls back to next candidate on 404`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "events": [ { "type": "insight", "message": "Battery low" } ] }""")
        )

        try {
            server.start()
            val result = modelFor(server).recentObservations()
            server.takeRequest()
            val secondRequest = server.takeRequest()

            assertEquals("/api/v1/proactive/state", secondRequest.path)
            val available = assertInstanceOf(ProactiveReadModel.Result.Available::class.java, result)
            assertEquals("insight", available.observations.first().title)
            assertEquals("Battery low", available.observations.first().detail)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `recentObservations reports Unavailable when every candidate fails`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            server.start()
            val result = modelFor(server).recentObservations()
            val unavailable = assertInstanceOf(ProactiveReadModel.Result.Unavailable::class.java, result)
            assertTrue(unavailable.reason.contains("Proactive observation feed is not reachable"))
        } finally {
            server.shutdown()
        }
    }
}
