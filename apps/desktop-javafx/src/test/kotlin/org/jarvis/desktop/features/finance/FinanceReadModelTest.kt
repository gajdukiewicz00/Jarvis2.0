package org.jarvis.desktop.features.finance

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Locale

class FinanceReadModelTest {

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

    private fun modelFor(server: MockWebServer) = FinanceReadModel(ApiClient(configFor(server)))

    @Test
    fun `listTransactions reverses expenses and caps at 20`() {
        val server = MockWebServer()
        val expenses = (1..25).joinToString(",") { i ->
            """{"amount":"$i.00","currency":"PLN","category":"food","description":"item-$i","createdAt":"2026-01-0${(i % 9) + 1}T10:00:00Z"}"""
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[$expenses]")
        )

        try {
            server.start()
            val transactions = modelFor(server).listTransactions()

            assertEquals(20, transactions.size)
            // Most recent (last in the source list) comes first after reversal.
            assertEquals("item-25", transactions.first().description)
            assertEquals("25.00", transactions.first().amount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `listTransactions returns empty list on malformed payload`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"not": "a list"}""")
        )

        try {
            server.start()
            assertTrue(modelFor(server).listTransactions().isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `parseNotification prefers top-level fields over nested expense fields`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "amount": "42.50", "currency": "USD", "category": "groceries", "merchant": "Whole Foods",
                      "expense": {"amount": "0", "currency": "EUR", "category": "other", "description": "fallback"}
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val parsed = modelFor(server).parseNotification("Card charged $42.50 at Whole Foods")

            assertEquals("42.50", parsed.amount)
            assertEquals("USD", parsed.currency)
            assertEquals("groceries", parsed.category)
            assertEquals("Whole Foods", parsed.merchant)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `parseNotification falls back to nested expense fields and defaults`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "expense": {"amount": "9.99", "currency": "PLN", "description": "Zabka"} }""")
        )

        try {
            server.start()
            val parsed = modelFor(server).parseNotification("Zabka charge")

            assertEquals("9.99", parsed.amount)
            assertEquals("PLN", parsed.currency)
            assertEquals("uncategorized", parsed.category)
            assertEquals("Zabka", parsed.merchant)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `parseNotification defaults amount to a question mark when nothing matches`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{}""")
        )

        try {
            server.start()
            val parsed = modelFor(server).parseNotification("unparseable text")

            assertEquals("?", parsed.amount)
            assertEquals("", parsed.currency)
            assertEquals("uncategorized", parsed.category)
            assertEquals("", parsed.merchant)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `addExpense posts the expense factory payload and echoes the input back`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))

        try {
            server.start()
            val transaction = modelFor(server).addExpense(
                amount = BigDecimal("15.00"),
                currency = "PLN",
                category = "transport",
                description = "bus ticket"
            )

            assertEquals("15.00", transaction.amount)
            assertEquals("PLN", transaction.currency)
            assertEquals("transport", transaction.category)
            assertEquals("bus ticket", transaction.description)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/life/finance/expenses"))
            assertTrue(request.body.readUtf8().contains("15.00"))
        } finally {
            server.shutdown()
        }
    }
}
