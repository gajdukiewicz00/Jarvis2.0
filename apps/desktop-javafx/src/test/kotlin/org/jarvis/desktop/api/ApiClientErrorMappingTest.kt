package org.jarvis.desktop.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.service.AuthService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Exercises [ApiClient]'s HTTP verb methods, error-code mapping, header
 * propagation, multipart encoding and the auth-retry loop against a
 * [MockWebServer]. Pure HTTP — no JavaFX toolkit required.
 */
class ApiClientErrorMappingTest {

    @AfterEach
    fun clearTokenState() {
        TokenManager.clearTokens()
    }

    private fun configFor(server: MockWebServer): () -> ResolvedDesktopConfig {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = baseUrl,
                apiBaseUrl = baseUrl,
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

    private fun clientFor(server: MockWebServer, authService: AuthService? = null): ApiClient =
        ApiClient(configFor(server), authService)

    private inline fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        try {
            server.start()
            block(server)
        } finally {
            server.shutdown()
        }
    }

    // ── GET success + headers ────────────────────────────────────────

    @Test
    fun `get returns body and sends model-profile and accept headers`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

            val body = clientFor(server).get("/status")

            assertEquals("""{"ok":true}""", body)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/status", request.path)
            assertEquals("application/json", request.getHeader("Accept"))
            assertEquals("desktop-general", request.getHeader("X-Model-Profile"))
        }
    }

    @Test
    fun `getWithHeaders propagates extra request headers`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            clientFor(server).getWithHeaders("/data", mapOf("X-Trace" to "abc-123"))

            assertEquals("abc-123", server.takeRequest().getHeader("X-Trace"))
        }
    }

    @Test
    fun `get attaches bearer authorization when a token is present`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            TokenManager.saveTokens("tok-xyz", "refresh", "alice", "USER")

            clientFor(server).get("/secure")

            assertEquals("Bearer tok-xyz", server.takeRequest().getHeader("Authorization"))
        }
    }

    // ── GET error-code mapping ───────────────────────────────────────

    @Test
    fun `get maps 403 to AccessDeniedException`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("nope"))

            val ex = assertThrows(AccessDeniedException::class.java) {
                clientFor(server).get("/admin")
            }
            assertTrue(ex.message!!.contains("Access denied", ignoreCase = true))
        }
    }

    @Test
    fun `get maps 404 to a resource-not-found message`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))

            val ex = assertThrows(Exception::class.java) {
                clientFor(server).get("/missing")
            }
            assertTrue(ex.message!!.contains("Resource not found (404)"))
        }
    }

    @Test
    fun `get maps 500 to a server-error message`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

            val ex = assertThrows(Exception::class.java) {
                clientFor(server).get("/broken")
            }
            assertTrue(ex.message!!.contains("Server error (500)"))
        }
    }

    @Test
    fun `get maps an unclassified 4xx to a raw HTTP message including the body`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("bad-input"))

            val ex = assertThrows(Exception::class.java) {
                clientFor(server).get("/bad")
            }
            assertTrue(ex.message!!.contains("HTTP 400"))
            assertTrue(ex.message!!.contains("bad-input"))
        }
    }

    // ── POST ─────────────────────────────────────────────────────────

    @Test
    fun `post returns body on 200 and sends json content type and body`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":1}"""))

            val body = clientFor(server).post("/items", """{"name":"x"}""")

            assertEquals("""{"id":1}""", body)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("application/json", request.getHeader("Content-Type"))
            assertEquals("""{"name":"x"}""", request.body.readUtf8())
        }
    }

    @Test
    fun `post treats 201 Created as success`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("created"))

            assertEquals("created", clientFor(server).post("/items", "{}"))
        }
    }

    @Test
    fun `post with custom read timeout still returns the body`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            assertEquals("ok", clientFor(server).post("/slow", "{}", 5_000))
        }
    }

    @Test
    fun `post maps a 500 error to a server-error message`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("down"))

            val ex = assertThrows(Exception::class.java) {
                clientFor(server).post("/items", "{}")
            }
            assertTrue(ex.message!!.contains("Server error (503)"))
        }
    }

    @Test
    fun `postWithHeaders propagates extra headers`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            clientFor(server).postWithHeaders("/x", "{}", mapOf("X-Corr" to "corr-9"))

            assertEquals("corr-9", server.takeRequest().getHeader("X-Corr"))
        }
    }

    // ── PUT ──────────────────────────────────────────────────────────

    @Test
    fun `put returns body on success`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("updated"))

            assertEquals("updated", clientFor(server).put("/items/1", """{"n":2}"""))
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("""{"n":2}""", request.body.readUtf8())
        }
    }

    @Test
    fun `put maps a 404 to a resource-not-found message`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))

            val ex = assertThrows(Exception::class.java) {
                clientFor(server).put("/items/9", "{}")
            }
            assertTrue(ex.message!!.contains("Resource not found (404)"))
        }
    }

    // ── DELETE ───────────────────────────────────────────────────────

    @Test
    fun `delete returns body on success`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("gone"))

            assertEquals("gone", clientFor(server).delete("/items/1"))
            assertEquals("DELETE", server.takeRequest().method)
        }
    }

    @Test
    fun `delete maps a 403 to AccessDeniedException`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("nope"))

            assertThrows(AccessDeniedException::class.java) {
                clientFor(server).delete("/items/1")
            }
        }
    }

    // ── multipart ────────────────────────────────────────────────────

    @Test
    fun `postMultipart encodes the file part and returns the body on success`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"transcript":"hi"}"""))

            val body = clientFor(server).postMultipart("/voice/stt", "clip.wav", "hello".toByteArray())

            assertEquals("""{"transcript":"hi"}""", body)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
            val sent = request.body.readUtf8()
            assertTrue(sent.contains("filename=\"clip.wav\""))
            assertTrue(sent.contains("Content-Type: audio/wav"))
            assertTrue(sent.contains("hello"))
        }
    }

    @Test
    fun `postMultipart maps an error response`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("bad"))

            val ex = assertThrows(Exception::class.java) {
                clientFor(server).postMultipart("/voice/stt", "clip.wav", byteArrayOf(1, 2, 3))
            }
            assertTrue(ex.message!!.contains("Server error (500)"))
        }
    }

    // ── auth-retry loop ──────────────────────────────────────────────

    @Test
    fun `get refreshes the token on 401 and retries the request successfully`() {
        withServer { server ->
            // 1) first GET -> 401  2) AuthService /auth/refresh -> new tokens  3) retry GET -> 200
            server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                    """
                    {"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":3600,"username":"alice","role":"USER"}
                    """.trimIndent()
                )
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("after-refresh"))

            TokenManager.saveTokens("old-access", "refresh-1", "alice", "USER")
            val client = clientFor(server, AuthService(configFor(server)))

            val body = client.get("/protected")

            assertEquals("after-refresh", body)
            // request #1 = GET, #2 = refresh POST, #3 = retried GET carrying refreshed token
            server.takeRequest()
            assertEquals("/auth/refresh", server.takeRequest().path)
            assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun `get gives up with a session-expired error when the retry is still unauthorized`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                    """
                    {"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":3600,"username":"alice","role":"USER"}
                    """.trimIndent()
                )
            )
            server.enqueue(MockResponse().setResponseCode(401).setBody("still expired"))

            TokenManager.saveTokens("old-access", "refresh-1", "alice", "USER")
            val client = clientFor(server, AuthService(configFor(server)))

            val ex = assertThrows(Exception::class.java) { client.get("/protected") }
            assertTrue(ex.message!!.contains("Session expired"))
        }
    }

    @Test
    fun `get fails fast with session-expired when no refresh is possible`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))

            // No AuthService and no refresh token -> refresh attempt returns false.
            val ex = assertThrows(Exception::class.java) { clientFor(server).get("/protected") }

            assertTrue(ex.message!!.contains("Session expired"))
            assertNotNull(ex.cause)
        }
    }
}
