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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceWebSocketClientProtocolTest {

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
    @DisplayName("voice websocket ERROR frames are surfaced as assistant responses")
    fun errorFramesAreSurfacedToUi() {
        val states = CopyOnWriteArrayList<String>()
        val responses = CopyOnWriteArrayList<Triple<String, String?, Boolean>>()
        val connectedLatch = CountDownLatch(1)
        val responseLatch = CountDownLatch(1)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    """
                    {
                      "type": "ERROR",
                      "code": "NO_AUDIO_RECEIVED",
                      "message": "Voice session ended before any audio was received.",
                      "correlationId": "corr-123"
                    }
                    """.trimIndent()
                )
            }
        }))
        server.start()

        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )

        client = VoiceWebSocketClient(
            urlProvider = { server.wsUrl("/ws/voice") },
            onStateChange = { state ->
                states += state
                if (state == "CONNECTED") {
                    connectedLatch.countDown()
                }
            },
            onTranscript = { _, _, _ -> },
            onResponse = { text, action, handled ->
                responses += Triple(text, action, handled)
                responseLatch.countDown()
            },
            onAudioReceived = {},
            uiDispatcher = { action -> action() }
        )

        client!!.connect()

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect")
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS), "protocol error should be surfaced")
        assertEquals(
            Triple("Voice session ended before any audio was received.", "NO_AUDIO_RECEIVED", false),
            responses.single()
        )
        assertTrue(states.contains("CONNECTED"))
        assertTrue(states.none { it.startsWith("ERROR:", ignoreCase = true) })
    }

    private fun MockWebServer.wsUrl(path: String): String {
        return url(path).toString().replaceFirst("http://", "ws://")
    }
}
