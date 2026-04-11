package org.jarvis.desktop.service

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.DesktopSettings
import org.jarvis.desktop.config.PreferencesDesktopSettingsStore
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VoiceWebSocketClientAuthRecoveryTest {

    private val server = MockWebServer()
    private val settingsStore = PreferencesDesktopSettingsStore()
    private var client: VoiceWebSocketClient? = null
    private lateinit var originalSettings: DesktopSettings

    @BeforeEach
    fun setUp() {
        originalSettings = settingsStore.load()
    }

    @AfterEach
    fun tearDown() {
        client?.disconnect()
        client = null
        TokenManager.clearTokens()
        settingsStore.save(originalSettings)
        AppConfig.reload()
        runCatching { server.shutdown() }
    }

    @Test
    @DisplayName("voice websocket 401 triggers refresh and reconnect without AUTH_REQUIRED")
    fun refreshesTokenAndReconnectsAfterUnauthorizedUpgrade() {
        val expiredAccessToken = jwt(subject = "user-123", marker = "expired")
        val refreshedAccessToken = jwt(subject = "user-123", marker = "fresh")
        val wsAttempts = AtomicInteger(0)
        val refreshRequestBody = AtomicReference<String>()
        val refreshRequestLatch = CountDownLatch(1)
        val connectedLatch = CountDownLatch(1)
        val authRequiredLatch = CountDownLatch(1)
        val states = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ws/voice" -> {
                        wsAttempts.incrementAndGet()
                        when (request.getHeader("Authorization")) {
                            "Bearer $expiredAccessToken" -> MockResponse().setResponseCode(401)
                            "Bearer $refreshedAccessToken" -> {
                                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                                    override fun onOpen(webSocket: WebSocket, response: Response) {
                                        // Keep the socket open for the client assertions.
                                    }
                                })
                            }
                            else -> MockResponse().setResponseCode(403)
                        }
                    }

                    "/auth/refresh" -> {
                        refreshRequestBody.set(request.body.readUtf8())
                        refreshRequestLatch.countDown()
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                """
                                {
                                  "accessToken": "$refreshedAccessToken",
                                  "refreshToken": "refresh-2",
                                  "expiresIn": 3600,
                                  "username": "alice",
                                  "role": "USER"
                                }
                                """.trimIndent()
                            )
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )

        TokenManager.saveTokens(expiredAccessToken, "refresh-1", "alice", "USER")
        client = newClient(
            config = serverConfig(),
            states = states,
            connectedLatch = connectedLatch,
            authRequiredLatch = authRequiredLatch,
            urlProvider = { server.wsUrl("/ws/voice") }
        )

        client!!.connect()

        assertTrue(refreshRequestLatch.await(5, TimeUnit.SECONDS), "refresh request should be sent")
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should reconnect")
        assertFalse(authRequiredLatch.await(500, TimeUnit.MILLISECONDS), "AUTH_REQUIRED should not be emitted")

        assertEquals("""{"refreshToken":"refresh-1"}""", refreshRequestBody.get())
        assertEquals(refreshedAccessToken, TokenManager.getAccessToken())
        assertEquals("refresh-2", TokenManager.getRefreshToken())
        assertEquals(2, wsAttempts.get())
        assertTrue(states.contains("Re-authenticating voice session..."))
        assertTrue(states.contains("CONNECTED"))
        assertFalse(states.any { it.startsWith("AUTH_REQUIRED") })
    }

    @Test
    @DisplayName("expired access token refreshes before first voice websocket connect")
    fun refreshesExpiredTokenBeforeFirstConnect() {
        val expiredAccessToken = jwt(subject = "user-123", marker = "expired", expEpochSeconds = epochSecondsFromNow(-60))
        val refreshedAccessToken = jwt(subject = "user-123", marker = "fresh", expEpochSeconds = epochSecondsFromNow(3600))
        val refreshAttempts = AtomicInteger(0)
        val wsAttempts = AtomicInteger(0)
        val requestOrder = CopyOnWriteArrayList<String>()
        val wsAuthorizationHeader = AtomicReference<String>()
        val connectedLatch = CountDownLatch(1)
        val authRequiredLatch = CountDownLatch(1)
        val states = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestOrder += request.path ?: ""
                return when (request.path) {
                    "/auth/refresh" -> {
                        refreshAttempts.incrementAndGet()
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                """
                                {
                                  "accessToken": "$refreshedAccessToken",
                                  "refreshToken": "refresh-2",
                                  "expiresIn": 3600,
                                  "username": "alice",
                                  "role": "USER"
                                }
                                """.trimIndent()
                            )
                    }

                    "/ws/voice" -> {
                        wsAttempts.incrementAndGet()
                        wsAuthorizationHeader.set(request.getHeader("Authorization"))
                        when (request.getHeader("Authorization")) {
                            "Bearer $refreshedAccessToken" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    // Keep the socket open for assertions.
                                }
                            })

                            "Bearer $expiredAccessToken" -> MockResponse().setResponseCode(401)
                            else -> MockResponse().setResponseCode(403)
                        }
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )

        TokenManager.saveTokens(expiredAccessToken, "refresh-1", "alice", "USER")
        client = newClient(
            config = serverConfig(),
            states = states,
            connectedLatch = connectedLatch,
            authRequiredLatch = authRequiredLatch,
            urlProvider = { server.wsUrl("/ws/voice") }
        )

        client!!.connect()

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect after pre-refresh")
        assertFalse(authRequiredLatch.await(500, TimeUnit.MILLISECONDS), "AUTH_REQUIRED should not be emitted")

        assertEquals(listOf("/auth/refresh", "/ws/voice"), requestOrder.toList())
        assertEquals(1, refreshAttempts.get())
        assertEquals(1, wsAttempts.get())
        assertEquals("Bearer $refreshedAccessToken", wsAuthorizationHeader.get())
        assertEquals(refreshedAccessToken, TokenManager.getAccessToken())
        assertEquals("refresh-2", TokenManager.getRefreshToken())
        assertTrue(states.contains("CONNECTED"))
        assertFalse(states.any { it.startsWith("AUTH_REQUIRED") })
    }

    @Test
    @DisplayName("near-expiry access token refreshes before first voice websocket connect")
    fun refreshesNearExpiryTokenBeforeFirstConnect() {
        val nearExpiryAccessToken = jwt(subject = "user-123", marker = "near-expiry", expEpochSeconds = epochSecondsFromNow(5))
        val refreshedAccessToken = jwt(subject = "user-123", marker = "fresh", expEpochSeconds = epochSecondsFromNow(3600))
        val refreshAttempts = AtomicInteger(0)
        val wsAttempts = AtomicInteger(0)
        val requestOrder = CopyOnWriteArrayList<String>()
        val wsAuthorizationHeader = AtomicReference<String>()
        val connectedLatch = CountDownLatch(1)
        val authRequiredLatch = CountDownLatch(1)
        val states = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestOrder += request.path ?: ""
                return when (request.path) {
                    "/auth/refresh" -> {
                        refreshAttempts.incrementAndGet()
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                """
                                {
                                  "accessToken": "$refreshedAccessToken",
                                  "refreshToken": "refresh-2",
                                  "expiresIn": 3600,
                                  "username": "alice",
                                  "role": "USER"
                                }
                                """.trimIndent()
                            )
                    }

                    "/ws/voice" -> {
                        wsAttempts.incrementAndGet()
                        wsAuthorizationHeader.set(request.getHeader("Authorization"))
                        when (request.getHeader("Authorization")) {
                            "Bearer $refreshedAccessToken" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    // Keep the socket open for assertions.
                                }
                            })

                            "Bearer $nearExpiryAccessToken" -> MockResponse().setResponseCode(401)
                            else -> MockResponse().setResponseCode(403)
                        }
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )

        TokenManager.saveTokens(nearExpiryAccessToken, "refresh-1", "alice", "USER")
        client = newClient(
            config = serverConfig(),
            states = states,
            connectedLatch = connectedLatch,
            authRequiredLatch = authRequiredLatch,
            urlProvider = { server.wsUrl("/ws/voice") }
        )

        client!!.connect()

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect after pre-refresh")
        assertFalse(authRequiredLatch.await(500, TimeUnit.MILLISECONDS), "AUTH_REQUIRED should not be emitted")

        assertEquals(listOf("/auth/refresh", "/ws/voice"), requestOrder.toList())
        assertEquals(1, refreshAttempts.get())
        assertEquals(1, wsAttempts.get())
        assertEquals("Bearer $refreshedAccessToken", wsAuthorizationHeader.get())
        assertEquals(refreshedAccessToken, TokenManager.getAccessToken())
        assertTrue(states.contains("CONNECTED"))
        assertFalse(states.any { it.startsWith("AUTH_REQUIRED") })
    }

    @Test
    @DisplayName("valid access token connects without pre-refresh")
    fun validTokenConnectsWithoutPreRefresh() {
        val validAccessToken = jwt(subject = "user-123", marker = "valid", expEpochSeconds = epochSecondsFromNow(3600))
        val refreshAttempts = AtomicInteger(0)
        val wsAttempts = AtomicInteger(0)
        val requestOrder = CopyOnWriteArrayList<String>()
        val wsAuthorizationHeader = AtomicReference<String>()
        val connectedLatch = CountDownLatch(1)
        val authRequiredLatch = CountDownLatch(1)
        val states = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestOrder += request.path ?: ""
                return when (request.path) {
                    "/auth/refresh" -> {
                        refreshAttempts.incrementAndGet()
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                """
                                {
                                  "accessToken": "$validAccessToken",
                                  "refreshToken": "refresh-2",
                                  "expiresIn": 3600,
                                  "username": "alice",
                                  "role": "USER"
                                }
                                """.trimIndent()
                            )
                    }

                    "/ws/voice" -> {
                        wsAttempts.incrementAndGet()
                        wsAuthorizationHeader.set(request.getHeader("Authorization"))
                        when (request.getHeader("Authorization")) {
                            "Bearer $validAccessToken" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    // Keep the socket open for assertions.
                                }
                            })

                            else -> MockResponse().setResponseCode(403)
                        }
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )

        TokenManager.saveTokens(validAccessToken, "refresh-1", "alice", "USER")
        client = newClient(
            config = serverConfig(),
            states = states,
            connectedLatch = connectedLatch,
            authRequiredLatch = authRequiredLatch,
            urlProvider = { server.wsUrl("/ws/voice") }
        )

        client!!.connect()

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect without pre-refresh")
        assertFalse(authRequiredLatch.await(500, TimeUnit.MILLISECONDS), "AUTH_REQUIRED should not be emitted")

        assertEquals(listOf("/ws/voice"), requestOrder.toList())
        assertEquals(0, refreshAttempts.get())
        assertEquals(1, wsAttempts.get())
        assertEquals("Bearer $validAccessToken", wsAuthorizationHeader.get())
        assertEquals(validAccessToken, TokenManager.getAccessToken())
        assertTrue(states.contains("CONNECTED"))
    }

    @Test
    @DisplayName("pre-connect refresh failure falls back to websocket auth recovery")
    fun preConnectRefreshFailureFallsBackToUnauthorizedRecovery() {
        val expiredAccessToken = jwt(subject = "user-123", marker = "expired", expEpochSeconds = epochSecondsFromNow(-60))
        val refreshAttempts = AtomicInteger(0)
        val wsAttempts = AtomicInteger(0)
        val requestOrder = CopyOnWriteArrayList<String>()
        val wsAuthorizationHeader = AtomicReference<String>()
        val connectedLatch = CountDownLatch(1)
        val authRequiredLatch = CountDownLatch(1)
        val states = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestOrder += request.path ?: ""
                return when (request.path) {
                    "/auth/refresh" -> {
                        refreshAttempts.incrementAndGet()
                        MockResponse()
                            .setResponseCode(500)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"error":"temporary refresh failure"}""")
                    }

                    "/ws/voice" -> {
                        wsAttempts.incrementAndGet()
                        wsAuthorizationHeader.set(request.getHeader("Authorization"))
                        when (request.getHeader("Authorization")) {
                            "Bearer $expiredAccessToken" -> MockResponse().setResponseCode(401)
                            else -> MockResponse().setResponseCode(403)
                        }
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )

        TokenManager.saveTokens(expiredAccessToken, "refresh-1", "alice", "USER")
        client = newClient(
            config = serverConfig(),
            states = states,
            connectedLatch = connectedLatch,
            authRequiredLatch = authRequiredLatch,
            urlProvider = { server.wsUrl("/ws/voice") }
        )

        client!!.connect()

        assertTrue(authRequiredLatch.await(5, TimeUnit.SECONDS), "AUTH_REQUIRED should be emitted after fallback recovery fails")
        assertFalse(connectedLatch.await(500, TimeUnit.MILLISECONDS), "voice websocket should not connect")

        assertEquals(listOf("/auth/refresh", "/ws/voice", "/auth/refresh"), requestOrder.toList())
        assertEquals(2, refreshAttempts.get())
        assertEquals(1, wsAttempts.get())
        assertEquals("Bearer $expiredAccessToken", wsAuthorizationHeader.get())
        assertTrue(states.any { it.startsWith("AUTH_REQUIRED") })
        assertFalse(states.contains("Re-authenticating voice session..."))
        assertNull(TokenManager.getAccessToken())
        assertNull(TokenManager.getRefreshToken())
    }

    @Test
    @DisplayName("voice websocket moves to AUTH_REQUIRED when refresh token is invalid")
    fun movesToAuthRequiredWhenRefreshFails() {
        val expiredAccessToken = jwt(subject = "user-123", marker = "expired")
        val wsAttempts = AtomicInteger(0)
        val refreshRequestBody = AtomicReference<String>()
        val refreshRequestLatch = CountDownLatch(1)
        val authRequiredLatch = CountDownLatch(1)
        val states = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ws/voice" -> {
                        wsAttempts.incrementAndGet()
                        MockResponse().setResponseCode(401)
                    }

                    "/auth/refresh" -> {
                        refreshRequestBody.set(request.body.readUtf8())
                        refreshRequestLatch.countDown()
                        MockResponse()
                            .setResponseCode(401)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"error":"invalid refresh token"}""")
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )

        TokenManager.saveTokens(expiredAccessToken, "refresh-expired", "alice", "USER")
        client = newClient(
            config = serverConfig(),
            states = states,
            connectedLatch = CountDownLatch(1),
            authRequiredLatch = authRequiredLatch,
            urlProvider = { server.wsUrl("/ws/voice") }
        )

        client!!.connect()

        assertTrue(refreshRequestLatch.await(5, TimeUnit.SECONDS), "refresh request should still be attempted")
        assertTrue(authRequiredLatch.await(5, TimeUnit.SECONDS), "AUTH_REQUIRED should be emitted")

        assertEquals("""{"refreshToken":"refresh-expired"}""", refreshRequestBody.get())
        assertEquals(1, wsAttempts.get())
        assertTrue(states.any { it.startsWith("AUTH_REQUIRED") })
        assertNull(TokenManager.getAccessToken())
        assertNull(TokenManager.getRefreshToken())
    }

    @Test
    @DisplayName("config update during auth recovery does not start a second reconnect")
    fun dedupesEndpointReconnectWhileAuthRecoveryIsRunning() {
        val replacementServer = MockWebServer()
        val expiredAccessToken = jwt(subject = "user-123", marker = "expired")
        val refreshedAccessToken = jwt(subject = "user-123", marker = "fresh")
        val originalWsAttempts = AtomicInteger(0)
        val replacementWsAttempts = AtomicInteger(0)
        val refreshStartedLatch = CountDownLatch(1)
        val allowRefreshResponseLatch = CountDownLatch(1)
        val connectedLatch = CountDownLatch(1)
        val authRequiredLatch = CountDownLatch(1)
        val states = CopyOnWriteArrayList<String>()

        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/ws/voice" -> {
                            originalWsAttempts.incrementAndGet()
                            MockResponse().setResponseCode(401)
                        }

                        "/auth/refresh" -> {
                            refreshStartedLatch.countDown()
                            assertTrue(
                                allowRefreshResponseLatch.await(5, TimeUnit.SECONDS),
                                "refresh response should be released by the test"
                            )
                            MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """
                                    {
                                      "accessToken": "$refreshedAccessToken",
                                      "refreshToken": "refresh-2",
                                      "expiresIn": 3600,
                                      "username": "alice",
                                      "role": "USER"
                                    }
                                    """.trimIndent()
                                )
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            replacementServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/ws/voice" -> {
                            replacementWsAttempts.incrementAndGet()
                            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    // Keep the socket open for assertions.
                                }
                            })
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            replacementServer.start()
            AppConfig.saveSettings(
                apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
                locale = Locale.ENGLISH,
                manualEndpointOverride = true
            )

            TokenManager.saveTokens(expiredAccessToken, "refresh-1", "alice", "USER")
            client = newClient(
                config = serverConfig(server),
                states = states,
                connectedLatch = connectedLatch,
                authRequiredLatch = authRequiredLatch,
                urlProvider = { AppConfig.current().voiceWebSocketUrl }
            )

            client!!.connect()

            assertTrue(refreshStartedLatch.await(5, TimeUnit.SECONDS), "refresh should start before config update")
            AppConfig.saveSettings(
                apiGatewayBaseUrl = replacementServer.url("/").toString().removeSuffix("/"),
                locale = Locale.ENGLISH,
                manualEndpointOverride = true
            )
            allowRefreshResponseLatch.countDown()

            assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should reconnect once")
            assertFalse(authRequiredLatch.await(500, TimeUnit.MILLISECONDS), "AUTH_REQUIRED should not be emitted")
            Thread.sleep(800L)

            assertEquals(1, originalWsAttempts.get())
            assertEquals(1, replacementWsAttempts.get())
            assertEquals(refreshedAccessToken, TokenManager.getAccessToken())
            assertTrue(states.contains("Re-authenticating voice session..."))
            assertTrue(states.contains("CONNECTED"))
            assertFalse(states.any { it.startsWith("AUTH_REQUIRED") })
        } finally {
            runCatching { replacementServer.shutdown() }
        }
    }

    @Test
    @DisplayName("endpoint reconnect cancels stale backoff reconnect")
    fun cancelsBackoffReconnectWhenEndpointChanges() {
        val replacementServer = MockWebServer()
        val validAccessToken = jwt(subject = "user-123", marker = "valid")
        val originalWsAttempts = AtomicInteger(0)
        val replacementWsAttempts = AtomicInteger(0)
        val backoffScheduledLatch = CountDownLatch(1)
        val connectedLatch = CountDownLatch(1)
        val authRequiredLatch = CountDownLatch(1)
        val states = CopyOnWriteArrayList<String>()

        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/ws/voice" -> {
                            originalWsAttempts.incrementAndGet()
                            MockResponse().setResponseCode(500)
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            replacementServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/ws/voice" -> {
                            replacementWsAttempts.incrementAndGet()
                            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    // Keep the socket open for assertions.
                                }
                            })
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            replacementServer.start()
            AppConfig.saveSettings(
                apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
                locale = Locale.ENGLISH,
                manualEndpointOverride = true
            )

            TokenManager.saveTokens(validAccessToken, "refresh-1", "alice", "USER")
            client = newClient(
                config = serverConfig(server),
                states = states,
                connectedLatch = connectedLatch,
                authRequiredLatch = authRequiredLatch,
                urlProvider = { AppConfig.current().voiceWebSocketUrl },
                stateObserver = { state ->
                    if (state.startsWith("Reconnecting in 2s")) {
                        backoffScheduledLatch.countDown()
                    }
                }
            )

            client!!.connect()

            assertTrue(backoffScheduledLatch.await(5, TimeUnit.SECONDS), "backoff reconnect should be scheduled")
            AppConfig.saveSettings(
                apiGatewayBaseUrl = replacementServer.url("/").toString().removeSuffix("/"),
                locale = Locale.ENGLISH,
                manualEndpointOverride = true
            )

            assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "endpoint update should trigger reconnect")
            assertFalse(authRequiredLatch.await(500, TimeUnit.MILLISECONDS), "AUTH_REQUIRED should not be emitted")
            Thread.sleep(2500L)

            assertEquals(1, originalWsAttempts.get())
            assertEquals(1, replacementWsAttempts.get())
            assertEquals(1, states.count { it == "CONNECTED" })
            assertFalse(states.any { it.startsWith("AUTH_REQUIRED") })
        } finally {
            runCatching { replacementServer.shutdown() }
        }
    }

    private fun newClient(
        config: ResolvedDesktopConfig,
        states: MutableList<String>,
        connectedLatch: CountDownLatch,
        authRequiredLatch: CountDownLatch,
        urlProvider: () -> String = { config.voiceWebSocketUrl },
        stateObserver: (String) -> Unit = {}
    ): VoiceWebSocketClient {
        return VoiceWebSocketClient(
            urlProvider = urlProvider,
            onStateChange = { state ->
                states += state
                stateObserver(state)
                if (state == "CONNECTED") {
                    connectedLatch.countDown()
                }
                if (state.startsWith("AUTH_REQUIRED")) {
                    authRequiredLatch.countDown()
                }
            },
            onTranscript = { _, _, _ -> },
            onResponse = { _, _, _ -> },
            onAudioReceived = {},
            authServiceFactory = { AuthService { config } },
            uiDispatcher = { action -> action() }
        )
    }

    private fun serverConfig(targetServer: MockWebServer = server): ResolvedDesktopConfig {
        val baseUrl = targetServer.url("/").toString().removeSuffix("/")
        return ResolvedDesktopConfig(
            apiGatewayBaseUrl = baseUrl,
            apiBaseUrl = "$baseUrl/api/v1",
            voiceWebSocketUrl = targetServer.wsUrl("/ws/voice"),
            pcControlWebSocketUrl = targetServer.wsUrl("/ws/pc-control"),
            locale = Locale.ENGLISH,
            voiceLanguage = "en-US",
            apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
            apiGatewayReason = "test",
            usesManualEndpointOverride = true
        )
    }

    private fun MockWebServer.wsUrl(path: String): String {
        return url(path).toString().replaceFirst("http://", "ws://")
    }

    private fun epochSecondsFromNow(offsetSeconds: Long): Long {
        return (System.currentTimeMillis() / 1000L) + offsetSeconds
    }

    private fun jwt(subject: String, marker: String, expEpochSeconds: Long? = null): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray(StandardCharsets.UTF_8))
        val payloadFields = buildList {
            add(""""sub":"$subject"""")
            add(""""marker":"$marker"""")
            expEpochSeconds?.let { add(""""exp":$it""") }
        }.joinToString(",")
        val payload = encoder.encodeToString("""{$payloadFields}""".toByteArray(StandardCharsets.UTF_8))
        return "$header.$payload.signature"
    }
}
