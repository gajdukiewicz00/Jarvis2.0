package org.jarvis.desktop.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.service.AuthService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * SECOND-WAVE coverage for [ApiClient]. The existing suites already cover the
 * GET happy/error paths and the GET auth-retry loop; this class fills the
 * remaining per-verb branches:
 *
 *  - the `isExpectedDegradedResponse` "expected degraded" debug branch (503
 *    FEATURE_DISABLED on GET, 404 on DELETE),
 *  - error-code mapping on POST / PUT / DELETE / multipart (403 / 404 / 5xx),
 *  - the connection-refused catch on POST / PUT / DELETE / multipart,
 *  - the SocketTimeout catch (via a custom-read-timeout POST),
 *  - the 401 → UnauthorizedRequestException path on POST / multipart, and
 *  - `attemptTokenRefresh` returning false both when the AuthService is absent
 *    (token present) and when the refresh call itself throws.
 *
 * Pure HTTP against a loopback [MockWebServer]; no JavaFX toolkit required.
 */
class ApiClientSecondWaveTest {

    @BeforeEach
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

    // ── isExpectedDegradedResponse "degraded" debug branch ────────────

    @Test
    fun `get logs a degraded 503 with FEATURE_DISABLED as expected and still maps to a server error`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("FEATURE_DISABLED: media pipeline off"))

            val ex = assertThrows(Exception::class.java) { clientFor(server).get("/media/jobs") }
            assertTrue(ex.message!!.contains("Server error (503)"))
        }
    }

    @Test
    fun `get logs a degraded 503 with UNSUPPORTED_RUNTIME_MODE as expected`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("UNSUPPORTED_RUNTIME_MODE in k8s"))

            val ex = assertThrows(Exception::class.java) { clientFor(server).get("/x") }
            assertTrue(ex.message!!.contains("Server error (503)"))
        }
    }

    @Test
    fun `get treats a plain 503 without a known code as a non-degraded failure`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily overloaded"))

            val ex = assertThrows(Exception::class.java) { clientFor(server).get("/y") }
            assertTrue(ex.message!!.contains("Server error (503)"))
        }
    }

    // ── DELETE error mapping (degraded 404 + 5xx) ─────────────────────

    @Test
    fun `delete logs a 404 as an expected degraded response and maps to not-found`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("gone already"))

            val ex = assertThrows(Exception::class.java) { clientFor(server).delete("/items/404") }
            assertTrue(ex.message!!.contains("Resource not found (404)"))
        }
    }

    @Test
    fun `delete maps a 500 to a server-error message`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("kaboom"))

            val ex = assertThrows(Exception::class.java) { clientFor(server).delete("/items/1") }
            assertTrue(ex.message!!.contains("Server error (500)"))
        }
    }

    // ── PUT error mapping (403 + 5xx) ─────────────────────────────────

    @Test
    fun `put maps a 403 to AccessDeniedException`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("nope"))

            assertThrows(AccessDeniedException::class.java) { clientFor(server).put("/items/1", "{}") }
        }
    }

    @Test
    fun `put maps a 500 to a server-error message`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("down"))

            val ex = assertThrows(Exception::class.java) { clientFor(server).put("/items/1", "{}") }
            assertTrue(ex.message!!.contains("Server error (500)"))
        }
    }

    // ── POST error mapping (403 + 404) ────────────────────────────────

    @Test
    fun `post maps a 403 to AccessDeniedException`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

            assertThrows(AccessDeniedException::class.java) { clientFor(server).post("/items", "{}") }
        }
    }

    @Test
    fun `post maps a 404 to a resource-not-found message`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))

            val ex = assertThrows(Exception::class.java) { clientFor(server).post("/items", "{}") }
            assertTrue(ex.message!!.contains("Resource not found (404)"))
        }
    }

    // ── multipart 5xx error mapping already covered elsewhere; add 403 ─

    @Test
    fun `postMultipart maps a 403 to AccessDeniedException`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))

            assertThrows(AccessDeniedException::class.java) {
                clientFor(server).postMultipart("/voice/stt", "clip.wav", byteArrayOf(1, 2, 3))
            }
        }
    }

    // ── 401 on POST / multipart flows into the session-expired path ───

    @Test
    fun `post on 401 without a refresh path gives up with session expired`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(401)) // empty error stream -> default "Unauthorized"

            val ex = assertThrows(Exception::class.java) { clientFor(server).post("/protected", "{}") }
            assertTrue(ex.message!!.contains("Session expired"))
        }
    }

    @Test
    fun `postMultipart on 401 without a refresh path gives up with session expired`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(401))

            val ex = assertThrows(Exception::class.java) {
                clientFor(server).postMultipart("/voice/stt", "clip.wav", byteArrayOf(9))
            }
            assertTrue(ex.message!!.contains("Session expired"))
        }
    }

    // ── connection-refused catch on each verb ─────────────────────────

    @Test
    fun `post surfaces a friendly message when the server is unavailable`() {
        val server = MockWebServer()
        server.start()
        val config = configFor(server)
        server.shutdown()

        val ex = assertThrows(Exception::class.java) { ApiClient(config).post("/x", "{}") }
        assertTrue(ex.message!!.contains("not available") || ex.message!!.contains("Connection refused"))
    }

    @Test
    fun `put surfaces a friendly message when the server is unavailable`() {
        val server = MockWebServer()
        server.start()
        val config = configFor(server)
        server.shutdown()

        val ex = assertThrows(Exception::class.java) { ApiClient(config).put("/x", "{}") }
        assertTrue(ex.message!!.contains("not available") || ex.message!!.contains("Connection refused"))
    }

    @Test
    fun `delete surfaces a friendly message when the server is unavailable`() {
        val server = MockWebServer()
        server.start()
        val config = configFor(server)
        server.shutdown()

        val ex = assertThrows(Exception::class.java) { ApiClient(config).delete("/x") }
        assertTrue(ex.message!!.contains("not available") || ex.message!!.contains("Connection refused"))
    }

    @Test
    fun `postMultipart surfaces a friendly message when the server is unavailable`() {
        val server = MockWebServer()
        server.start()
        val config = configFor(server)
        server.shutdown()

        val ex = assertThrows(Exception::class.java) {
            ApiClient(config).postMultipart("/x", "a.wav", byteArrayOf(1))
        }
        assertTrue(ex.message!!.contains("not available") || ex.message!!.contains("Connection refused"))
    }

    // ── SocketTimeout catch (custom read timeout on POST) ─────────────

    @Test
    fun `post surfaces a timeout message when the server stalls past the read timeout`() {
        withServer { server ->
            // Delay the response headers well past the short read timeout so the
            // socket read raises SocketTimeoutException.
            server.enqueue(MockResponse().setBody("late").setHeadersDelay(3, TimeUnit.SECONDS))

            val ex = assertThrows(Exception::class.java) {
                clientFor(server).post("/slow", "{}", readTimeoutMs = 250)
            }
            assertTrue(ex.message!!.contains("timeout", ignoreCase = true))
        }
    }

    // ── attemptTokenRefresh negative branches ─────────────────────────

    @Test
    fun `unauthorized request with a token but no AuthService clears tokens and reports session expired`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
            // Token present, but the client has no AuthService -> refresh is impossible.
            TokenManager.saveTokens("stale-access", "stale-refresh", "alice", "USER")

            val ex = assertThrows(Exception::class.java) { clientFor(server, authService = null).get("/protected") }

            assertTrue(ex.message!!.contains("Session expired"))
            assertNull(TokenManager.getAccessToken())
            assertNull(TokenManager.getRefreshToken())
        }
    }

    @Test
    fun `unauthorized request whose refresh call itself fails reports session expired and clears tokens`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("expired")) // original GET
            server.enqueue(MockResponse().setResponseCode(500).setBody("refresh boom")) // /auth/refresh fails

            TokenManager.saveTokens("old-access", "refresh-1", "alice", "USER")
            val client = clientFor(server, AuthService(configFor(server)))

            val ex = assertThrows(Exception::class.java) { client.get("/protected") }

            assertTrue(ex.message!!.contains("Session expired"))
            assertNull(TokenManager.getAccessToken())
        }
    }
}
