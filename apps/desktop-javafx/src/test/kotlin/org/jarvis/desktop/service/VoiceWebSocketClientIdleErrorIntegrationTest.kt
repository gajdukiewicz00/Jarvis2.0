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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression test for the "connected but DEGRADED" contradiction: a
 * NO_AUDIO_RECEIVED ERROR frame is an idle outcome and must never flip
 * STT/TTS availability or emit a connection-level state change.
 */
class VoiceWebSocketClientIdleErrorIntegrationTest {

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
    @DisplayName("NO_AUDIO_RECEIVED does not toggle STT/TTS availability or connection state")
    fun noAudioReceivedDoesNotDegradeStatus() {
        val states = CopyOnWriteArrayList<String>()
        val sttChanges = CopyOnWriteArrayList<Boolean>()
        val ttsChanges = CopyOnWriteArrayList<Boolean>()
        val responses = CopyOnWriteArrayList<Triple<String, String?, Boolean>>()
        val connectedLatch = CountDownLatch(1)
        val protocolErrorLatch = CountDownLatch(1)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    """
                    {
                      "type": "ERROR",
                      "code": "NO_AUDIO_RECEIVED",
                      "message": "Voice session ended before any audio was received.",
                      "correlationId": "corr-idle"
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
            onResponse = { t, a, h -> responses += Triple(t, a, h) },
            onAudioReceived = {},
            onSttStatusChanged = { available, _ -> sttChanges += available },
            onTtsStatusChanged = { available, _ -> ttsChanges += available },
            uiDispatcher = { action -> action() },
            onProtocolError = { _, _ -> protocolErrorLatch.countDown() }
        )

        client!!.connect()

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect")
        assertTrue(protocolErrorLatch.await(5, TimeUnit.SECONDS),
            "idle outcome should reach the protocol-error sink (diagnostics), not the response log")

        assertTrue(responses.isEmpty(), "NO_AUDIO_RECEIVED must never be shown as a Jarvis response")
        assertTrue(sttChanges.isEmpty(), "NO_AUDIO_RECEIVED must not change STT availability")
        assertTrue(ttsChanges.isEmpty(), "NO_AUDIO_RECEIVED must not change TTS availability")
        assertTrue(
            states.none { it.contains("DEGRADED", ignoreCase = true) },
            "NO_AUDIO_RECEIVED must never surface as a DEGRADED connection state"
        )
        assertTrue(states.contains("CONNECTED"))
    }

    private fun MockWebServer.wsUrl(path: String): String {
        return url(path).toString().replaceFirst("http://", "ws://")
    }
}
