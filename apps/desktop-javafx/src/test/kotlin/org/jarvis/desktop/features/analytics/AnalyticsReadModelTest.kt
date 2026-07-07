package org.jarvis.desktop.features.analytics

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class AnalyticsReadModelTest {

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

    private fun modelFor(server: MockWebServer) = AnalyticsReadModel(ApiClient(configFor(server)))

    @Test
    fun `refresh parses monthly, category, and trend data on success`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[{"period":"2026-05","category":"All","totalAmount":"120.50","currency":"$","count":4},
                    {"period":"2026-06","category":"All","totalAmount":"80.00","currency":"$","count":2}]"""
            )
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[{"period":"All","category":"Food","totalAmount":"60.00","currency":"$","count":3},
                    {"period":"All","category":"Transport","totalAmount":"40.50","currency":"$","count":1}]"""
            )
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"type":"line","labels":["Jan","Feb","Mar"],"values":[10,20,15],
                    "title":"Expense trend","xAxisLabel":"Month","yAxisLabel":"Amount"}"""
            )
        )

        try {
            server.start()
            val snapshot = modelFor(server).refresh()

            val byMonth = snapshot.byMonth
            check(byMonth is AnalyticsReadModel.Result.Available) { "expected byMonth to be Available, was $byMonth" }
            assertEquals(2, byMonth.data.size)
            assertEquals("2026-05", byMonth.data[0].label)
            assertEquals(120.50, byMonth.data[0].amount, 0.001)
            assertEquals("$", byMonth.data[0].currency)
            assertEquals(4, byMonth.data[0].count)

            val byCategory = snapshot.byCategory
            check(byCategory is AnalyticsReadModel.Result.Available) { "expected byCategory to be Available, was $byCategory" }
            assertEquals(2, byCategory.data.size)
            assertEquals("Food", byCategory.data[0].label)
            assertEquals(60.00, byCategory.data[0].amount, 0.001)

            val trend = snapshot.trend
            check(trend is AnalyticsReadModel.Result.Available) { "expected trend to be Available, was $trend" }
            assertEquals("Expense trend", trend.data.title)
            assertEquals("Month", trend.data.xAxisLabel)
            assertEquals(listOf("Jan" to 10.0, "Feb" to 20.0, "Mar" to 15.0), trend.data.points)

            assertEquals("/api/v1/analytics/expenses/by-month", server.takeRequest().path)
            assertEquals("/api/v1/analytics/expenses/by-category", server.takeRequest().path)
            assertTrue(server.takeRequest().path!!.startsWith("/api/v1/analytics/expenses/trend"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh degrades each probe independently to Unavailable`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"labels":[],"values":[]}"""))

        try {
            server.start()
            val snapshot = modelFor(server).refresh()

            assertTrue(snapshot.byMonth is AnalyticsReadModel.Result.Unavailable)
            assertTrue(snapshot.byCategory is AnalyticsReadModel.Result.Unavailable)

            val trend = snapshot.trend
            check(trend is AnalyticsReadModel.Result.Available) { "expected trend to be Available, was $trend" }
            assertTrue(trend.data.points.isEmpty())
        } finally {
            server.shutdown()
        }
    }
}
