package org.jarvis.desktop.service

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.DesktopSettings
import org.jarvis.desktop.config.PreferencesDesktopSettingsStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the inbound-frame branches of [VoiceWebSocketClient.onMessage]:
 * STATE (with STT/TTS availability), partial/final transcripts, RESPONSE
 * frames carrying STT_UNAVAILABLE / TTS_UNAVAILABLE actions, ERROR frames
 * with the same failure codes, ACTION_RESULT, and a binary TTS audio frame.
 * The server sends every frame in order over a single upgraded socket and the
 * binary audio frame acts as the barrier, so all prior text frames are
 * guaranteed to have been processed by the time the audio latch releases.
 */
class VoiceWebSocketClientMessageDispatchTest {

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
    @DisplayName("inbound STATE/transcript/response/error/audio frames route to their callbacks")
    fun dispatchesEveryInboundFrameType() {
        val states = CopyOnWriteArrayList<String>()
        val transcripts = CopyOnWriteArrayList<Triple<String, Boolean, String?>>()
        val responses = CopyOnWriteArrayList<Triple<String, String?, Boolean>>()
        val sttStatuses = CopyOnWriteArrayList<Pair<Boolean, String?>>()
        val ttsStatuses = CopyOnWriteArrayList<Pair<Boolean, String?>>()
        val audioFrames = CopyOnWriteArrayList<ByteArray>()
        val protocolErrors = CopyOnWriteArrayList<Pair<String, String?>>()
        val audioLatch = CountDownLatch(1)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"STATE","state":"PROCESSING","sttAvailable":true,"ttsAvailable":true,"ttsReason":"ready"}""")
                webSocket.send("""{"type":"TRANSCRIPT_PARTIAL","text":"hello","correlationId":"c-1"}""")
                webSocket.send("""{"type":"TRANSCRIPT_FINAL","text":"hello world","correlationId":"c-1"}""")
                webSocket.send("""{"type":"RESPONSE","text":"stt down","action":"STT_UNAVAILABLE","handled":false}""")
                webSocket.send("""{"type":"RESPONSE","text":"tts down","action":"TTS_UNAVAILABLE","handled":true}""")
                webSocket.send("""{"type":"ERROR","code":"STT_UNAVAILABLE","message":"stt error"}""")
                webSocket.send("""{"type":"ERROR","code":"TTS_UNAVAILABLE","message":"tts error"}""")
                webSocket.send("""{"type":"ACTION_RESULT","success":true,"message":"done"}""")
                webSocket.send(byteArrayOf(1, 2, 3, 4).toByteString())
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
            onStateChange = { states += it },
            onTranscript = { text, isFinal, corr -> transcripts += Triple(text, isFinal, corr) },
            onResponse = { text, action, handled -> responses += Triple(text, action, handled) },
            onAudioReceived = { bytes ->
                audioFrames += bytes
                audioLatch.countDown()
            },
            onSttStatusChanged = { available, reason -> sttStatuses += (available to reason) },
            onTtsStatusChanged = { available, reason -> ttsStatuses += (available to reason) },
            uiDispatcher = { action -> action() },
            onProtocolError = { code, message -> protocolErrors += (code to message) }
        )
        client!!.connect()

        assertTrue(audioLatch.await(5, TimeUnit.SECONDS), "audio barrier frame should be delivered")

        assertTrue(states.contains("PROCESSING"), "STATE frame should surface the reported state")
        assertEquals(Triple("hello", false, "c-1"), transcripts.first { !it.second })
        assertEquals(Triple("hello world", true, "c-1"), transcripts.first { it.second })

        assertTrue(responses.contains(Triple("stt down", "STT_UNAVAILABLE", false)))
        assertTrue(responses.contains(Triple("tts down", "TTS_UNAVAILABLE", true)))
        // ERROR frames route to the protocol-error sink (diagnostics) and set STT/TTS status,
        // but are NEVER delivered to onResponse / the assistant response log.
        assertTrue(protocolErrors.contains("STT_UNAVAILABLE" to "stt error"))
        assertTrue(protocolErrors.contains("TTS_UNAVAILABLE" to "tts error"))
        assertFalse(
            responses.any { it.first == "stt error" || it.first == "tts error" },
            "ERROR frames must never be delivered as assistant responses"
        )

        assertTrue(sttStatuses.contains(true to null), "STATE frame reports STT availability")
        assertTrue(sttStatuses.contains(false to "stt down"), "RESPONSE STT_UNAVAILABLE marks STT down")
        assertTrue(sttStatuses.contains(false to "stt error"), "ERROR STT_UNAVAILABLE marks STT down")

        assertTrue(ttsStatuses.contains(true to "ready"), "STATE frame reports TTS availability + reason")
        assertTrue(ttsStatuses.contains(false to "tts down"), "RESPONSE TTS_UNAVAILABLE marks TTS down")
        assertTrue(ttsStatuses.contains(false to "tts error"), "ERROR TTS_UNAVAILABLE marks TTS down")

        assertEquals(1, audioFrames.size)
        assertTrue(audioFrames.single().contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertFalse(states.any { it.startsWith("ERROR:", ignoreCase = true) })
    }

    @Test
    @DisplayName("malformed inbound frame is swallowed without breaking the session")
    fun malformedFrameIsIgnored() {
        val states = CopyOnWriteArrayList<String>()
        val connectedLatch = CountDownLatch(1)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("this is not json")
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
                if (state == "CONNECTED") connectedLatch.countDown()
            },
            onTranscript = { _, _, _ -> },
            onResponse = { _, _, _ -> },
            onAudioReceived = {},
            uiDispatcher = { action -> action() }
        )
        client!!.connect()

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect")
        // A non-JSON frame must not crash the reader or emit an ERROR state.
        assertFalse(states.any { it.startsWith("ERROR:", ignoreCase = true) })
    }

    private fun MockWebServer.wsUrl(path: String): String {
        return url(path).toString().replaceFirst("http://", "ws://")
    }
}
