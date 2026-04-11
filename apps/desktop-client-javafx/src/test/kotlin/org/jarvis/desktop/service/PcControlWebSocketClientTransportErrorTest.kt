package org.jarvis.desktop.service

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.DesktopSettings
import org.jarvis.desktop.config.PreferencesDesktopSettingsStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PcControlWebSocketClientTransportErrorTest {

    private val server = MockWebServer()
    private val settingsStore = PreferencesDesktopSettingsStore()
    private lateinit var originalSettings: DesktopSettings
    private var client: PcControlWebSocketClient? = null

    @BeforeEach
    fun setUp() {
        originalSettings = settingsStore.load()
    }

    @AfterEach
    fun tearDown() {
        client?.disconnect()
        TokenManager.clearTokens()
        settingsStore.save(originalSettings)
        AppConfig.reload()
        runCatching { server.shutdown() }
    }

    @Test
    @DisplayName("pc-control websocket transport failure keeps actionable status")
    fun nullTransportMessageUsesActionableFallback() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Keep the socket open for assertions.
            }
        }))
        server.start()

        val statuses = CopyOnWriteArrayList<String>()
        val connectedLatch = CountDownLatch(1)
        client = PcControlWebSocketClient(
            urlProvider = { server.wsUrl("/ws/pc-control") },
            systemControl = SystemControlService(),
            onStatusChange = { status ->
                statuses += status
                if (status == "Connected") {
                    connectedLatch.countDown()
                }
            },
            uiDispatcher = { action -> action() }
        )

        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )
        client!!.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "pc-control websocket should connect before failure")

        reconnectAttemptsField().setInt(client, 10)
        val liveSocket = socketField().get(client) as WebSocket

        client!!.onFailure(liveSocket, UnknownHostException(), null)

        val latestStatus = statuses.last()
        assertFalse(latestStatus.contains("null", ignoreCase = true))
        assertTrue(
            latestStatus.contains("host", ignoreCase = true) ||
                latestStatus.contains("dns", ignoreCase = true)
        )
    }

    private fun reconnectAttemptsField() = PcControlWebSocketClient::class.java.getDeclaredField("reconnectAttempts").apply {
        isAccessible = true
    }

    private fun socketField() = PcControlWebSocketClient::class.java.getDeclaredField("webSocket").apply {
        isAccessible = true
    }

    private fun MockWebServer.wsUrl(path: String): String {
        return url(path).toString().replaceFirst("http://", "ws://")
    }
}
