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
    @DisplayName("voice websocket ERROR frames route to onProtocolError, never the response log")
    fun errorFramesAreNotSurfacedAsResponses() {
        val states = CopyOnWriteArrayList<String>()
        val responses = CopyOnWriteArrayList<Triple<String, String?, Boolean>>()
        val protocolErrors = CopyOnWriteArrayList<Pair<String, String?>>()
        val connectedLatch = CountDownLatch(1)
        val errorLatch = CountDownLatch(1)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    """
                    {
                      "type": "ERROR",
                      "code": "END_NOT_ALLOWED",
                      "message": "END is only valid while audio is streaming.",
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
            },
            onAudioReceived = {},
            uiDispatcher = { action -> action() },
            onProtocolError = { code, message ->
                protocolErrors += (code to message)
                errorLatch.countDown()
            }
        )

        client!!.connect()

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect")
        assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "protocol error should reach onProtocolError")
        assertEquals(
            "END_NOT_ALLOWED" to "END is only valid while audio is streaming.",
            protocolErrors.single()
        )
        // The reported bug: a gateway ERROR frame must NEVER be rendered as a "Jarvis: ..." response.
        assertTrue(responses.isEmpty(), "ERROR frames must not be delivered to onResponse / the response log")
        assertTrue(states.contains("CONNECTED"))
        assertTrue(states.none { it.startsWith("ERROR:", ignoreCase = true) })
    }

    private fun MockWebServer.wsUrl(path: String): String {
        return url(path).toString().replaceFirst("http://", "ws://")
    }
}
