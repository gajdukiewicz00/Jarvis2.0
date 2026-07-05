package org.jarvis.desktop.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.service.AuthService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Covers [ApiClient] HTTP verbs, error mapping, and the auth-retry loop
 * against a real (loopback) [MockWebServer] rather than mocks — this
 * mirrors the project's existing "fakes/real server over mocking
 * frameworks" convention (no Mockito dependency in this module).
 */
class ApiClientTest {

    @AfterEach
    fun tearDown() {
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

    @Test
    fun `get sends Accept and model-profile headers and returns body`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        try {
            server.start()
            val client = ApiClient(configFor(server))
            val body = client.get("/status")
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals("/status", request.path)
            assertEquals("application/json", request.getHeader("Accept"))
            assertEquals("desktop-general", request.getHeader("X-Model-Profile"))
            assertEquals("""{"ok":true}""", body)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `getWithHeaders adds extra headers on top of defaults`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))

        try {
            server.start()
            val client = ApiClient(configFor(server))
            client.getWithHeaders("/status", mapOf("X-Test" to "yes"))
            val request = server.takeRequest()

            assertEquals("yes", request.getHeader("X-Test"))
            assertEquals("desktop-general", request.getHeader("X-Model-Profile"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `post sends body with json content type`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201).setBody("created"))

        try {
            server.start()
            val client = ApiClient(configFor(server))
            val response = client.post("/things", """{"name":"widget"}""")
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals("application/json", request.getHeader("Content-Type"))
            assertEquals("""{"name":"widget"}""", request.body.readUtf8())
            assertEquals("created", response)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `put sends body and succeeds on 2xx`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("updated"))

        try {
            server.start()
            val client = ApiClient(configFor(server))
            val response = client.put("/things/1", """{"name":"widget2"}""")
            val request = server.takeRequest()

            assertEquals("PUT", request.method)
            assertEquals("updated", response)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `delete succeeds on 2xx`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(""))

        try {
            server.start()
            val client = ApiClient(configFor(server))
            client.delete("/things/1")
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `postMultipart writes multipart body with filename`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"frameId":"f-1"}"""))

        try {
            server.start()
            val client = ApiClient(configFor(server))
            val response = client.postMultipart("/upload", "clip.wav", byteArrayOf(1, 2, 3))
            val request = server.takeRequest()

            assertTrue(request.getHeader("Content-Type")?.contains("multipart/form-data") == true)
            assertTrue(request.body.readUtf8().contains("filename=\"clip.wav\""))
            assertEquals("""{"frameId":"f-1"}""", response)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `403 maps to AccessDeniedException`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

        try {
            server.start()
            val client = ApiClient(configFor(server))
            val ex = assertThrows(AccessDeniedException::class.java) { client.get("/secret") }
            assertTrue(ex.message!!.contains("Access denied"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `404 maps to a resource-not-found message`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            server.start()
            val client = ApiClient(configFor(server))
            val ex = assertThrows(Exception::class.java) { client.get("/missing") }
            assertTrue(ex.message!!.contains("not found"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `5xx maps to a server-error message`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        try {
            server.start()
            val client = ApiClient(configFor(server))
            val ex = assertThrows(Exception::class.java) { client.post("/things", "{}") }
            assertTrue(ex.message!!.contains("Server error"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `connection refused maps to a friendly server-unavailable message`() {
        val server = MockWebServer()
        server.start()
        val configProvider = configFor(server)
        server.shutdown() // Nothing is listening now -> ConnectException on every call.

        val client = ApiClient(configProvider)
        val ex = assertThrows(Exception::class.java) { client.get("/status") }
        assertTrue(ex.message!!.contains("not available") || ex.message!!.contains("Connection refused"))
    }

    @Test
    fun `unauthorized GET retries once after a successful token refresh then succeeds`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "accessToken": "fresh-access",
                      "refreshToken": "fresh-refresh",
                      "expiresIn": 3600,
                      "username": "alice",
                      "role": "USER"
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(MockResponse().setBody("refreshed-body"))

        try {
            server.start()
            TokenManager.saveTokens("expired-access", "refresh-1", "alice", "USER")

            val authService = AuthService(configFor(server))
            val client = ApiClient(configFor(server), authService)
            val response = client.get("/protected")

            assertEquals("/protected", server.takeRequest().path)
            assertEquals("/auth/refresh", server.takeRequest().path)
            assertEquals("/protected", server.takeRequest().path)
            assertEquals("refreshed-body", response)
            assertEquals("fresh-access", TokenManager.getAccessToken())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `unauthorized GET without a refresh token gives up with a session-expired message`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            server.start()
            TokenManager.clearTokens()
            val client = ApiClient(configFor(server), AuthService(configFor(server)))
            val ex = assertThrows(Exception::class.java) { client.get("/protected") }
            assertTrue(ex.message!!.contains("Session expired"))
        } finally {
            server.shutdown()
        }
    }
}
