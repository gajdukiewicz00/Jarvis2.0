package org.jarvis.desktop.features.brain

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class BrainChatReadModelTest {

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

    private fun modelFor(server: MockWebServer) = BrainChatReadModel(ApiClient(configFor(server)))

    @Test
    fun `chat parses OpenAI-style choices reply and posts trimmed prompt`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    { "choices": [ { "message": { "content": "Good evening, sir." } } ], "model": "qwen3-14b" }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val reply = modelFor(server).chat("  hello there  ")
            val request = server.takeRequest()

            assertEquals("/api/v1/llm/chat", request.path)
            assertTrue(request.body.readUtf8().contains("\"content\":\"hello there\""))
            assertEquals("Good evening, sir.", reply.message)
            assertEquals("qwen3-14b", reply.model)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `chat falls back through alternate reply fields`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "response": "All systems nominal." }""")
        )

        try {
            server.start()
            val reply = modelFor(server).chat("status")
            assertEquals("All systems nominal.", reply.message)
            assertNull(reply.model)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `chat falls back to raw body when response is not JSON`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("plain text reply"))

        try {
            server.start()
            val reply = modelFor(server).chat("hi")
            assertEquals("plain text reply", reply.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `chat reports empty reply placeholder when body is blank`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(""))

        try {
            server.start()
            val reply = modelFor(server).chat("hi")
            assertEquals("(empty reply)", reply.message)
        } finally {
            server.shutdown()
        }
    }
}
