package org.jarvis.desktop.features.memory

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `recentNotes appends the scope query parameter when a scope is given`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[]")
        )

        try {
            server.start()
            modelFor(server).recentNotes(limit = 5, scope = "FINANCE")

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/memory/notes"))
            assertTrue(request.path!!.contains("limit=5"))
            assertTrue(request.path!!.contains("scope=FINANCE"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `recentNotes omits the scope query parameter when scope is null or blank`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[]")
        )

        try {
            server.start()
            modelFor(server).recentNotes(limit = 5, scope = "  ")

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("limit=5"))
            assertFalse(request.path!!.contains("scope="))
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

    @Test
    fun `why parses source, privacy, scope and pin state`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"memoryId": "m-1", "source": "voice", "confidence": 0.75, "scope": "FINANCE",
                     "privacy": "LOCAL_ONLY", "pinned": true, "createdAt": "2026-01-01T10:00:00Z",
                     "explanation": "Jarvis remembered this because it came from voice."}
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val info = modelFor(server).why("m-1")

            assertEquals("voice", info.source)
            assertEquals(0.75, info.confidence)
            assertEquals("FINANCE", info.scope)
            assertEquals("LOCAL_ONLY", info.privacy)
            assertTrue(info.pinned)
            assertEquals("2026-01-01T10:00:00Z", info.createdAt)

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/memory/notes/m-1/why"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pinNote PUTs to the pin path`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))

        try {
            server.start()
            modelFor(server).pinNote("m-1")

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertTrue(request.path!!.contains("/memory/notes/m-1/pin"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `unpinNote DELETEs the pin path`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))

        try {
            server.start()
            modelFor(server).unpinNote("m-1")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertTrue(request.path!!.contains("/memory/notes/m-1/pin"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `changeScope PUTs the new scope as a query parameter`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))

        try {
            server.start()
            modelFor(server).changeScope("m-1", "FINANCE")

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertTrue(request.path!!.contains("/memory/notes/m-1/scope"))
            assertTrue(request.path!!.contains("scope=FINANCE"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `recentNotes parses pinned and scope fields when present`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"memoryId": "m-1", "title": "Budget note", "body": "text", "scope": "FINANCE", "pinned": true}
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val items = modelFor(server).recentNotes()

            assertEquals(1, items.size)
            assertTrue(items[0].pinned)
            assertEquals("FINANCE", items[0].scope)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `exportEncryptedOrPlain appends the scope query parameter when a scope is given`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"encrypted":false,"notes":[]}"""))

        try {
            server.start()
            modelFor(server).exportEncryptedOrPlain(scope = "FINANCE")

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.path!!.contains("/memory/notes/export/encrypted-or-plain"))
            assertTrue(request.path!!.contains("scope=FINANCE"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `exportEncryptedOrPlain omits the scope query parameter and returns the raw body when scope is null`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"encrypted":false,"notes":[]}"""))

        try {
            server.start()
            val payload = modelFor(server).exportEncryptedOrPlain()

            val request = server.takeRequest()
            assertFalse(request.path!!.contains("scope="))
            assertTrue(payload.contains("\"encrypted\":false"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `importFile posts a bare note array to import-resolve with the encoded mode and strips unknown fields`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"received":1,"created":1,"overwritten":0,"skipped":0,"failed":0,"errors":[]}""")
        )

        try {
            server.start()
            val content = """
                [
                  {"memoryId":"m-1","title":"Note","body":"text","status":"ACTIVE","contentHash":"abc","embedding":[0.1,0.2]}
                ]
            """.trimIndent()
            val result = modelFor(server).importFile(content, "keep-both")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/memory/notes/import/resolve"))
            assertTrue(request.path!!.contains("mode=keep-both"))
            val sentBody = request.body.readUtf8()
            assertTrue(sentBody.contains("\"memoryId\":\"m-1\""))
            assertFalse(sentBody.contains("contentHash"))
            assertFalse(sentBody.contains("embedding"))
            assertFalse(sentBody.contains("status"))

            assertEquals(1, result.received)
            assertEquals(1, result.created)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `importFile routes a raw encrypted envelope to import-encrypted-resolve`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"received":2,"created":2,"overwritten":0,"skipped":0,"failed":0,"errors":[]}""")
        )

        try {
            server.start()
            val content = """{"algorithm":"AES/GCM/NoPadding","ivBase64":"aaaa","ciphertextBase64":"bbbb"}"""
            val result = modelFor(server).importFile(content, "skip")

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/memory/notes/import/encrypted/resolve"))
            assertTrue(request.path!!.contains("mode=skip"))
            assertTrue(request.body.readUtf8().contains("ciphertextBase64"))
            assertEquals(2, result.received)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `importFile unwraps an encrypted export-wrapper envelope to import-encrypted-resolve`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody("""{"received":0,"created":0,"overwritten":0,"skipped":0,"failed":0,"errors":[]}""")
        )

        try {
            server.start()
            val content = """
                {"encrypted":true,"envelope":{"algorithm":"AES/GCM/NoPadding","ivBase64":"aaaa","ciphertextBase64":"bbbb"},"notes":null}
            """.trimIndent()
            modelFor(server).importFile(content, "overwrite")

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/memory/notes/import/encrypted/resolve"))
            val sentBody = request.body.readUtf8()
            assertTrue(sentBody.contains("ciphertextBase64"))
            assertFalse(sentBody.contains("\"envelope\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `importFile unwraps a plaintext export-wrapper notes array to import-resolve and sanitizes fields`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody("""{"received":1,"created":0,"overwritten":1,"skipped":0,"failed":0,"errors":[]}""")
        )

        try {
            server.start()
            val content = """
                {"encrypted":false,"envelope":null,"notes":[
                  {"memoryId":"m-9","title":"T","body":"B","vaultRelativePath":"x/y.md","pinned":true}
                ]}
            """.trimIndent()
            val result = modelFor(server).importFile(content, "overwrite")

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/memory/notes/import/resolve"))
            val sentBody = request.body.readUtf8()
            assertTrue(sentBody.contains("\"memoryId\":\"m-9\""))
            assertFalse(sentBody.contains("vaultRelativePath"))
            assertFalse(sentBody.contains("pinned"))
            assertEquals(1, result.overwritten)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `importFile throws for an unrecognized payload shape`() {
        val server = MockWebServer()

        try {
            server.start()
            val model = modelFor(server)
            assertThrows(IllegalArgumentException::class.java) {
                model.importFile("""{"foo":"bar"}""", "skip")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `forgetByQuery appends query, scope, actor and reason and parses the count and memoryIds`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"count":2,"memoryIds":["m-1","m-2"]}""")
        )

        try {
            server.start()
            val result = modelFor(server).forgetByQuery("old notes", "FINANCE", "owner", "cleanup")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertTrue(request.path!!.contains("/memory/notes/by-query"))
            assertTrue(request.path!!.contains("query="))
            assertTrue(request.path!!.contains("scope=FINANCE"))
            assertTrue(request.path!!.contains("actor=owner"))
            assertTrue(request.path!!.contains("reason="))

            assertEquals(2, result.count)
            assertEquals(listOf("m-1", "m-2"), result.memoryIds)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `forgetByQuery omits query and scope params when both are null`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"count":0,"memoryIds":[]}"""))

        try {
            server.start()
            modelFor(server).forgetByQuery(null, null, null, null)

            val request = server.takeRequest()
            assertFalse(request.path!!.contains("?"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `forgetMany aggregates forgotten and failed ids across sequential per-id requests`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        try {
            server.start()
            val result = modelFor(server).forgetMany(listOf("m-1", "m-2", "m-3"), "owner", "bulk cleanup")

            assertEquals(3, result.requested)
            assertEquals(listOf("m-1", "m-3"), result.forgotten)
            assertEquals(listOf("m-2"), result.failed)
        } finally {
            server.shutdown()
        }
    }
}
