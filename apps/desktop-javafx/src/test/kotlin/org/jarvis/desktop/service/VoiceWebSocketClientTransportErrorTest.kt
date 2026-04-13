package org.jarvis.desktop.service

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.DesktopSettings
import org.jarvis.desktop.config.PreferencesDesktopSettingsStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceWebSocketClientTransportErrorTest {

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
        settingsStore.save(originalSettings)
        AppConfig.reload()
        runCatching { server.shutdown() }
    }

    @Test
    @DisplayName("voice websocket transport failure does not surface literal null")
    fun nullTransportMessageIsSanitized() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Keep the socket open for assertions.
            }
        }))
        server.start()

        val states = CopyOnWriteArrayList<String>()
        val connectedLatch = CountDownLatch(1)
        client = VoiceWebSocketClient(
            urlProvider = { server.wsUrl("/ws/voice") },
            onStateChange = { state ->
                states += state
                if (state == "CONNECTED") {
                    connectedLatch.countDown()
                }
            },
            onTranscript = { _, _, _ -> },
            onResponse = { _, _, _ -> },
            onAudioReceived = {},
            uiDispatcher = { action -> action() }
        )

        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )
        client!!.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect before failure")

        shouldReconnectField().setBoolean(client, false)
        val liveSocket = socketField().get(client) as WebSocket

        client!!.onFailure(liveSocket, ConnectException(), null)

        assertFalse(states.any { it.contains("null", ignoreCase = true) })
        assertTrue(states.any { it.startsWith("ERROR:", ignoreCase = true) })
    }

    private fun socketField() = VoiceWebSocketClient::class.java.getDeclaredField("webSocket").apply {
        isAccessible = true
    }

    private fun shouldReconnectField() = VoiceWebSocketClient::class.java.getDeclaredField("shouldReconnect").apply {
        isAccessible = true
    }

    private fun MockWebServer.wsUrl(path: String): String {
        return url(path).toString().replaceFirst("http://", "ws://")
    }
}
