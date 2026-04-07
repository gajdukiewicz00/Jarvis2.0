package org.jarvis.desktop.service

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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

class PcControlWebSocketClientAuthRecoveryTest {

    private val server = MockWebServer()
    private var client: PcControlWebSocketClient? = null
    private lateinit var originalConfig: ResolvedDesktopConfig

    @BeforeEach
    fun setUp() {
        originalConfig = AppConfig.current()
    }

    @AfterEach
    fun tearDown() {
        client?.disconnect()
        client = null
        TokenManager.clearTokens()
        AppConfig.saveSettings(
            apiGatewayBaseUrl = originalConfig.apiGatewayBaseUrl,
            locale = originalConfig.locale,
            manualEndpointOverride = originalConfig.usesManualEndpointOverride
        )
        runCatching { server.shutdown() }
    }

    @Test
    @DisplayName("pc-control websocket 401 triggers refresh and reconnect")
    fun refreshesTokenAndReconnectsAfterUnauthorizedUpgrade() {
        val expiredAccessToken = jwt(subject = "user-123", marker = "expired")
        val refreshedAccessToken = jwt(subject = "user-123", marker = "fresh")
        val refreshRequestBody = AtomicReference<String>()
        val refreshRequestLatch = CountDownLatch(1)
        val connectedLatch = CountDownLatch(1)
        val wsAttempts = AtomicInteger(0)
        val statuses = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/ws/pc-control" -> {
                        wsAttempts.incrementAndGet()
                        when (request.getHeader("Authorization")) {
                            "Bearer $expiredAccessToken" -> MockResponse().setResponseCode(401)
                            "Bearer $refreshedAccessToken" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    // Keep the socket open for assertions.
                                }
                            })

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
        client = newClient(statuses, connectedLatch) { server.wsUrl("/ws/pc-control") }

        client!!.connect()

        assertTrue(refreshRequestLatch.await(5, TimeUnit.SECONDS), "refresh request should be sent")
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "pc-control websocket should reconnect")
        assertEquals("""{"refreshToken":"refresh-1"}""", refreshRequestBody.get())
        assertEquals(refreshedAccessToken, TokenManager.getAccessToken())
        assertEquals("refresh-2", TokenManager.getRefreshToken())
        assertEquals(2, wsAttempts.get())
        assertTrue(statuses.contains("Connected"))
    }

    @Test
    @DisplayName("expired access token refreshes before first pc-control websocket connect")
    fun refreshesExpiredTokenBeforeFirstConnect() {
        val expiredAccessToken = jwt(subject = "user-123", marker = "expired", expEpochSeconds = epochSecondsFromNow(-60))
        val refreshedAccessToken = jwt(subject = "user-123", marker = "fresh", expEpochSeconds = epochSecondsFromNow(3600))
        val refreshAttempts = AtomicInteger(0)
        val wsAttempts = AtomicInteger(0)
        val requestOrder = CopyOnWriteArrayList<String>()
        val wsAuthorizationHeader = AtomicReference<String>()
        val connectedLatch = CountDownLatch(1)
        val statuses = CopyOnWriteArrayList<String>()

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

                    "/ws/pc-control" -> {
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
        client = newClient(statuses, connectedLatch) { server.wsUrl("/ws/pc-control") }

        client!!.connect()

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "pc-control websocket should connect after pre-refresh")
        assertEquals(listOf("/auth/refresh", "/ws/pc-control"), requestOrder.toList())
        assertEquals(1, refreshAttempts.get())
        assertEquals(1, wsAttempts.get())
        assertEquals("Bearer $refreshedAccessToken", wsAuthorizationHeader.get())
        assertEquals(refreshedAccessToken, TokenManager.getAccessToken())
        assertEquals("refresh-2", TokenManager.getRefreshToken())
        assertTrue(statuses.contains("Connected"))
    }

    private fun newClient(
        statuses: MutableList<String>,
        connectedLatch: CountDownLatch,
        urlProvider: () -> String
    ): PcControlWebSocketClient {
        val config = serverConfig()
        return PcControlWebSocketClient(
            urlProvider = urlProvider,
            systemControl = SystemControlService(),
            onStatusChange = { status ->
                statuses += status
                if (status == "Connected") {
                    connectedLatch.countDown()
                }
            },
            authServiceFactory = { AuthService { config } },
            uiDispatcher = { action -> action() }
        )
    }

    private fun serverConfig(): ResolvedDesktopConfig {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return ResolvedDesktopConfig(
            apiGatewayBaseUrl = baseUrl,
            apiBaseUrl = "$baseUrl/api/v1",
            voiceWebSocketUrl = server.wsUrl("/ws/voice"),
            pcControlWebSocketUrl = server.wsUrl("/ws/pc-control"),
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
