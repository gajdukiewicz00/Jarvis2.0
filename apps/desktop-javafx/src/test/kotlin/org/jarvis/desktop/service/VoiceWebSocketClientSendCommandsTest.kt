package org.jarvis.desktop.service

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.DesktopSettings
import org.jarvis.desktop.config.PreferencesDesktopSettingsStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises the outbound command surface of [VoiceWebSocketClient]
 * (startCommand / sendConfig / sendAudio / endOfSpeech / requestTimeoutPhrase /
 * clearCorrelationId) plus their not-connected guards and disconnect().
 */
class VoiceWebSocketClientSendCommandsTest {

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
    @DisplayName("connected client forwards start/config/audio/end/timeout frames to the server")
    fun sendsCommandFramesWhenConnected() {
        val textFrames = CopyOnWriteArrayList<String>()
        val binaryFrames = CopyOnWriteArrayList<ByteString>()
        val serverGotAudio = CountDownLatch(1)
        val serverGotTimeout = CountDownLatch(1)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                textFrames += text
                if (text.contains("\"TIMEOUT\"")) serverGotTimeout.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                binaryFrames += bytes
                serverGotAudio.countDown()
            }
        }))
        server.start()

        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )

        val connectedLatch = CountDownLatch(1)
        client = VoiceWebSocketClient(
            urlProvider = { server.wsUrl("/ws/voice") },
            onStateChange = { state -> if (state == "CONNECTED") connectedLatch.countDown() },
            onTranscript = { _, _, _ -> },
            onResponse = { _, _, _ -> },
            onAudioReceived = {},
            uiDispatcher = { action -> action() }
        )
        client!!.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect")

        client!!.isSendingAllowed = true
        client!!.startCommand("corr-42")
        assertEquals("corr-42", client!!.currentCorrelationId)
        client!!.sendConfig(mapOf("language" to "en-US"))
        client!!.sendAudio(byteArrayOf(9, 8, 7))
        client!!.endOfSpeech()
        client!!.requestTimeoutPhrase()

        assertTrue(serverGotAudio.await(5, TimeUnit.SECONDS), "audio frame should reach the server")
        assertTrue(serverGotTimeout.await(5, TimeUnit.SECONDS), "timeout frame should reach the server")

        assertTrue(textFrames.any { it.contains("\"START\"") && it.contains("corr-42") }, "START frame")
        assertTrue(textFrames.any { it.contains("\"CONFIG\"") }, "CONFIG frame")
        assertTrue(textFrames.any { it.contains("\"END\"") && it.contains("corr-42") }, "END frame")
        assertTrue(textFrames.any { it.contains("\"TIMEOUT\"") && it.contains("corr-42") }, "TIMEOUT frame")
        assertTrue(binaryFrames.any { it.toByteArray().contentEquals(byteArrayOf(9, 8, 7)) }, "audio bytes")

        client!!.clearCorrelationId()
        assertNull(client!!.currentCorrelationId)
    }

    @Test
    @DisplayName("END is suppressed without an active stream and deduplicated per correlation")
    fun endIsGuardedAndDeduplicated() {
        val textFrames = CopyOnWriteArrayList<String>()
        val connectedLatch = CountDownLatch(1)
        val serverGotStart = CountDownLatch(1)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                textFrames += text
                if (text.contains("\"START\"")) serverGotStart.countDown()
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
            onStateChange = { state -> if (state == "CONNECTED") connectedLatch.countDown() },
            onTranscript = { _, _, _ -> },
            onResponse = { _, _, _ -> },
            onAudioReceived = {},
            uiDispatcher = { action -> action() }
        )
        client!!.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect")

        val c = client!!
        // 1. END before any START (no active stream) must be dropped client-side — this is
        //    what previously provoked the gateway's "END is only valid while audio is streaming".
        c.endOfSpeech()
        // 2. Start a stream, then END twice — only the first END should be sent.
        c.startCommand("corr-dup")
        assertTrue(serverGotStart.await(5, TimeUnit.SECONDS), "START should reach the server")
        c.endOfSpeech()
        c.endOfSpeech()
        Thread.sleep(300)

        val endFrames = textFrames.filter { it.contains("\"END\"") }
        assertEquals(1, endFrames.size, "exactly one END should be sent (stray + duplicate suppressed)")
        assertTrue(endFrames.single().contains("corr-dup"), "the single END carries the active correlationId")
    }

    @Test
    @DisplayName("audio is dropped while sending is disallowed even when connected")
    fun audioSuppressedWhenSendingNotAllowed() {
        val binaryFrames = CopyOnWriteArrayList<ByteString>()
        val connectedLatch = CountDownLatch(1)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                binaryFrames += bytes
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
            onStateChange = { state -> if (state == "CONNECTED") connectedLatch.countDown() },
            onTranscript = { _, _, _ -> },
            onResponse = { _, _, _ -> },
            onAudioReceived = {},
            uiDispatcher = { action -> action() }
        )
        client!!.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "voice websocket should connect")

        client!!.isSendingAllowed = false
        client!!.sendAudio(byteArrayOf(1, 2, 3))
        // Give any (erroneous) send a chance to arrive before asserting nothing did.
        Thread.sleep(200)
        assertTrue(binaryFrames.isEmpty(), "audio must be suppressed when sending is disallowed")
    }

    @Test
    @DisplayName("command methods are safe no-ops before the socket connects")
    fun commandsAreNoOpsWhenDisconnected() {
        AppConfig.saveSettings(
            apiGatewayBaseUrl = "http://127.0.0.1:1",
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )
        client = VoiceWebSocketClient(
            urlProvider = { "ws://127.0.0.1:1/ws/voice" },
            onStateChange = {},
            onTranscript = { _, _, _ -> },
            onResponse = { _, _, _ -> },
            onAudioReceived = {},
            uiDispatcher = { action -> action() }
        )

        val c = client!!
        assertFalse(c.isConnected)
        assertDoesNotThrow {
            c.isSendingAllowed = true
            c.sendAudio(byteArrayOf(1))
            c.sendConfig(mapOf("language" to "en-US"))
            c.sendConfig(emptyMap())
            c.endOfSpeech()
            c.requestTimeoutPhrase()
        }
        // startCommand records the correlation id even while disconnected, then bails out.
        c.startCommand("corr-x")
        assertEquals("corr-x", c.currentCorrelationId)
        c.clearCorrelationId()
        assertNull(c.currentCorrelationId)
    }

    @Test
    @DisplayName("disconnect stops the session and emits DISCONNECTED")
    fun disconnectEmitsDisconnectedState() {
        val states = CopyOnWriteArrayList<String>()
        val connectedLatch = CountDownLatch(1)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {}
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

        client!!.disconnect()
        assertFalse(client!!.isConnected)
        assertTrue(states.contains("DISCONNECTED"))
    }

    private fun MockWebServer.wsUrl(path: String): String {
        return url(path).toString().replaceFirst("http://", "ws://")
    }
}
