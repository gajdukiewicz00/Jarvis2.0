package org.jarvis.desktop.features.finance

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class FinanceReviewReadModelTest {

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

    private fun modelFor(server: MockWebServer) = FinanceReviewReadModel(ApiClient(configFor(server)))

    @Test
    fun `parseBatch parses the needsReview drafts and counts`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "imported": 1,
                      "totalRows": 3,
                      "needsReview": [
                        {
                          "valid": true, "confidence": "MEDIUM", "needsReview": true,
                          "amount": "45.00", "currency": "PLN", "merchant": "Lidl", "type": "EXPENSE",
                          "category": "groceries", "cardMask": "**** 1234", "dedupKey": "abc",
                          "occurredAt": "2026-01-01T10:00:00", "rawMasked": "masked text",
                          "notes": ["ambiguous currency"], "storedId": null
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val result = modelFor(server).parseBatch("line1\nline2\nline3")

            assertEquals(1, result.imported)
            assertEquals(3, result.totalRows)
            assertEquals(1, result.drafts.size)
            assertEquals("MEDIUM", result.drafts[0].confidence)
            assertTrue(result.drafts[0].needsReview)
            assertEquals("45.00", result.drafts[0].amount)
            assertEquals("Lidl", result.drafts[0].merchant)
            assertEquals("groceries", result.drafts[0].category)
            assertEquals(listOf("ambiguous currency"), result.drafts[0].notes)

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/life/finance/import-csv-notifications"))
            assertTrue(request.body.readUtf8().contains("line1"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `parseBatch returns an empty draft list when needsReview is absent`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"imported": 2, "totalRows": 2}""")
        )

        try {
            server.start()
            val result = modelFor(server).parseBatch("line1\nline2")

            assertEquals(2, result.imported)
            assertTrue(result.drafts.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `approve posts the draft as a real expense`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))

        try {
            server.start()
            val draft = FinanceReviewReadModel.Draft(
                confidence = "LOW",
                needsReview = true,
                amount = "12.30",
                currency = "EUR",
                merchant = "Uber",
                category = "transport",
                cardMask = "**** 5678",
                occurredAt = "2026-01-01T10:00:00",
                rawMasked = "masked",
                notes = emptyList()
            )
            modelFor(server).approve(draft)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/life/finance/expenses"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("12.3") || body.contains("12.30"))
            assertTrue(body.contains("EUR"))
            assertTrue(body.contains("transport"))
            assertTrue(body.contains("Uber"))
        } finally {
            server.shutdown()
        }
    }
}
