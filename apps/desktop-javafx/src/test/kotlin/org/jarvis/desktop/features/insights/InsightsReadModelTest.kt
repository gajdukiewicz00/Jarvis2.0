package org.jarvis.desktop.features.insights

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.Locale

class InsightsReadModelTest {

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

    private fun modelFor(server: MockWebServer) = InsightsReadModel(ApiClient(configFor(server)))

    @Test
    fun `refresh probes all four endpoints independently`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"insights":true}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"forecast":"sunny"}"""))
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            server.start()
            val snapshot = modelFor(server).refresh()

            assertInstanceOf(InsightsReadModel.Result.Available::class.java, snapshot.insights)
            assertInstanceOf(InsightsReadModel.Result.Unavailable::class.java, snapshot.dayScore)
            assertInstanceOf(InsightsReadModel.Result.Available::class.java, snapshot.forecast)
            assertInstanceOf(InsightsReadModel.Result.Unavailable::class.java, snapshot.report)

            assertEquals("/api/v1/analytics/insights", server.takeRequest().path)
            assertEquals("/api/v1/analytics/insights/day-score", server.takeRequest().path)
            assertEquals("/api/v1/analytics/insights/forecast", server.takeRequest().path)
            assertEquals("/api/v1/analytics/insights/report", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }
}
