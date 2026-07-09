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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises the reconnect-exhaustion and live-close branches of
 * [VoiceWebSocketClient] that the happy-path/auth tests never reach: the
 * "give up after N attempts" UNAVAILABLE state emitted by scheduleReconnect
 * once reconnectAttempts is already at the cap (driven via both onFailure and
 * a non-normal onClosed), the live-socket onClosing branch, and the
 * language-only config change that resends a CONFIG frame instead of switching
 * endpoints. reconnectAttempts is seeded to the cap via reflection so no real
 * backoff reconnect is ever scheduled — the branch is deterministic.
 */
class VoiceWebSocketClientReconnectBranchTest {

    private val server = MockWebServer()
    private val settingsStore = PreferencesDesktopSettingsStore()
    private lateinit var originalSettings: DesktopSettings
    private var client: VoiceWebSocketClient? = null

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
    @DisplayName("onFailure at the reconnect cap surfaces an UNAVAILABLE state")
    fun failureAtCapEmitsUnavailable() {
        val states = CopyOnWriteArrayList<String>()
        val client = connectedClient(states)
        val liveSocket = socketField().get(client) as WebSocket

        // Pin reconnectAttempts at the ceiling so scheduleReconnect gives up.
        reconnectAttemptsField().setInt(client, maxReconnectAttempts(client))

        client.onFailure(liveSocket, ConnectException("boom"), null)

        assertTrue(
            states.any { it.startsWith("UNAVAILABLE") },
            "exhausted reconnects must surface an UNAVAILABLE state"
        )
    }

    @Test
    @DisplayName("non-normal onClosed at the cap disconnects then reports UNAVAILABLE")
    fun nonNormalCloseAtCapReportsUnavailable() {
        val states = CopyOnWriteArrayList<String>()
        val client = connectedClient(states)
        val liveSocket = socketField().get(client) as WebSocket

        reconnectAttemptsField().setInt(client, maxReconnectAttempts(client))

        // Live-socket onClosing branch: logs only, keeps the connection flagged.
        client.onClosing(liveSocket, 1001, "server going away")
        assertTrue(client.isConnected, "onClosing must not tear down the live connection")

        client.onClosed(liveSocket, 1011, "internal error")

        assertTrue(states.contains("DISCONNECTED"), "a non-normal close should report DISCONNECTED")
        assertTrue(
            states.any { it.startsWith("UNAVAILABLE") },
            "a non-normal close past the cap should also surface UNAVAILABLE"
        )
    }

    @Test
    @DisplayName("a language-only config change resends CONFIG without switching endpoints")
    fun languageOnlyChangeResendsConfig() {
        val configFrames = CopyOnWriteArrayList<String>()
        val russianConfigLatch = CountDownLatch(1)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"CONFIG\"")) {
                    configFrames += text
                    if (text.contains("ru-RU")) russianConfigLatch.countDown()
                }
            }
        }))
        server.start()

        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )

        val states = CopyOnWriteArrayList<String>()
        val connectedLatch = CountDownLatch(1)
        client = VoiceWebSocketClient(
            // Track the resolved config URL so a language-only change is NOT seen
            // as an endpoint change (the endpoint stays identical).
            urlProvider = { AppConfig.current().voiceWebSocketUrl },
            onStateChange = { state ->
                states += state
                if (state == "CONNECTED") connectedLatch.countDown()
            },
            onTranscript = { _, _, _ -> },
            onResponse = { _, _, _ -> },
            onAudioReceived = {},
            uiDispatcher = { action -> action() }
        )
        client!!.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect")

        // Same base URL, different locale => voiceLanguage flips en-US -> ru-RU.
        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale("ru", "RU"),
            manualEndpointOverride = true
        )

        assertTrue(
            russianConfigLatch.await(5, TimeUnit.SECONDS),
            "a language-only change should resend a CONFIG frame with the new language"
        )
        assertTrue(client!!.isConnected, "the socket should stay connected across a language change")
    }

    private fun connectedClient(states: MutableList<String>): VoiceWebSocketClient {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Keep the socket open for assertions.
            }
        }))
        server.start()

        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )

        val connectedLatch = CountDownLatch(1)
        val created = VoiceWebSocketClient(
            urlProvider = { server.wsUrl("/ws/voice") },
            onStateChange = { state ->
                states += state
                if (state == "CONNECTED") connectedLatch.countDown()
            },
            onTranscript = { _, _, _ -> },
            onResponse = { _, _, _ -> },
            onAudioReceived = {},
            uiDispatcher = { action -> action() }
        )
        client = created
        created.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect")
        return created
    }

    private fun maxReconnectAttempts(target: VoiceWebSocketClient): Int {
        return VoiceWebSocketClient::class.java.getDeclaredField("maxReconnectAttempts").apply {
            isAccessible = true
        }.getInt(target)
    }

    private fun reconnectAttemptsField() = VoiceWebSocketClient::class.java.getDeclaredField("reconnectAttempts").apply {
        isAccessible = true
    }

    private fun socketField() = VoiceWebSocketClient::class.java.getDeclaredField("webSocket").apply {
        isAccessible = true
    }

    private fun MockWebServer.wsUrl(path: String): String {
        return url(path).toString().replaceFirst("http://", "ws://")
    }
}
