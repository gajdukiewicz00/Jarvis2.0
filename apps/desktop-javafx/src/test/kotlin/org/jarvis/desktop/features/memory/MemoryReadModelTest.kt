package org.jarvis.desktop.features.memory

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

class MemoryReadModelTest {

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

    private fun modelFor(server: MockWebServer) = MemoryReadModel(ApiClient(configFor(server)))

    @Test
    fun `search parses a results-wrapped payload into memory items`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "results": [
                        {"memoryId": "m-1", "title": "Vision bridge note", "snippet": "webcam setup", "source": "memory", "score": 0.9},
                        {"id": "c-1", "content": "chunk text", "source": "conversation"}
                      ]
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val items = modelFor(server).search("vision")

            assertEquals(2, items.size)
            assertEquals("m-1", items[0].memoryId)
            assertEquals("Vision bridge note", items[0].title)
            assertEquals("webcam setup", items[0].snippet)
            assertEquals(0.9, items[0].score)
            assertTrue(items[0].isManageable)

            assertEquals("chunk text", items[1].snippet)
            assertFalse(items[1].isManageable)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `recentNotes parses a bare array payload and falls back to title candidates`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"name": "job-search-aqa.md", "text": "Junior QA hunt", "type": "note"},
                      {"path": "gpu-cuda-blackwell-mismatch.md"}
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val items = modelFor(server).recentNotes(limit = 5)

            assertEquals(2, items.size)
            assertEquals("job-search-aqa.md", items[0].title)
            assertEquals("Junior QA hunt", items[0].snippet)
            assertEquals("note", items[0].source)
            assertEquals("gpu-cuda-blackwell-mismatch.md", items[1].title)
            assertEquals("", items[1].snippet)
            assertEquals("memory", items[1].source)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `parseItems returns empty list when no known container is present`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "status": "ok" }""")
        )

        try {
            server.start()
            val items = modelFor(server).recentNotes()
            assertTrue(items.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `getNote prefers body over summary and memoryId over id`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": "fallback-id", "memoryId": "m-42", "title": "Note", "summary": "short", "body": "full body"}""")
        )

        try {
            server.start()
            val note = modelFor(server).getNote("m-42")

            assertEquals("m-42", note.memoryId)
            assertEquals("Note", note.title)
            assertEquals("full body", note.body)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `getNote falls back to the requested memoryId when the response omits it`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"title": "Note", "summary": "only summary"}""")
        )

        try {
            server.start()
            val note = modelFor(server).getNote("requested-id")

            assertEquals("requested-id", note.memoryId)
            assertEquals("only summary", note.body)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `updateNote PUTs a trimmed title and body to the encoded note path`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))

        try {
            server.start()
            modelFor(server).updateNote("m 42", "  New title  ", "body text")

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertTrue(request.path!!.contains("/memory/notes/m%2042") || request.path!!.contains("/memory/notes/m+42"))
            assertTrue(request.body.readUtf8().contains("New title"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `forgetNote appends actor and reason as query parameters when present`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))

        try {
            server.start()
            modelFor(server).forgetNote("m-1", "owner", "user requested deletion")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertTrue(request.path!!.contains("actor=owner"))
            assertTrue(request.path!!.contains("reason="))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `forgetNote omits query parameters when actor and reason are blank`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))

        try {
            server.start()
            modelFor(server).forgetNote("m-1", null, "  ")

            val request = server.takeRequest()
            assertFalse(request.path!!.contains("?"))
        } finally {
            server.shutdown()
        }
    }
}
