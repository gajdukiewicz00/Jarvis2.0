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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers the "no availability change" false-branches of
 * [VoiceWebSocketClient.onMessage] that the sibling
 * [VoiceWebSocketClientMessageDispatchTest] (which always carries STT/TTS
 * signals) does not exercise:
 *  - a STATE frame WITHOUT `sttAvailable` / `ttsAvailable`
 *    (both `!= null` guards fall through, no status callback fires);
 *  - a RESPONSE frame with NO action and `handled=true`
 *    (both `action == "*_UNAVAILABLE"` guards fall through);
 *  - a frame whose `type` matches none of the `when` arms.
 *
 * A trailing binary frame is the barrier: once the audio callback fires, every
 * preceding text frame has been processed on the (inline) dispatcher.
 */
class VoiceWebSocketClientStateBranchTest {

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
    @DisplayName("STATE without flags and an action-less RESPONSE route without touching STT/TTS status")
    fun statelessAndActionlessFramesLeaveAvailabilityUntouched() {
        val states = CopyOnWriteArrayList<String>()
        val responses = CopyOnWriteArrayList<Triple<String, String?, Boolean>>()
        val sttStatuses = CopyOnWriteArrayList<Pair<Boolean, String?>>()
        val ttsStatuses = CopyOnWriteArrayList<Pair<Boolean, String?>>()
        val audioLatch = CountDownLatch(1)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // STATE with no sttAvailable/ttsAvailable -> both null-guards fall through.
                webSocket.send("""{"type":"STATE","state":"LISTENING"}""")
                // RESPONSE with no action and handled=true -> both *_UNAVAILABLE ifs fall through.
                webSocket.send("""{"type":"RESPONSE","text":"all good","handled":true}""")
                // Unknown type -> matches no `when` arm.
                webSocket.send("""{"type":"MYSTERY_FRAME","foo":"bar"}""")
                // Barrier.
                webSocket.send(byteArrayOf(7, 7).toByteString())
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
            onTranscript = { _, _, _ -> },
            onResponse = { text, action, handled -> responses += Triple(text, action, handled) },
            onAudioReceived = { audioLatch.countDown() },
            onSttStatusChanged = { available, reason -> sttStatuses += (available to reason) },
            onTtsStatusChanged = { available, reason -> ttsStatuses += (available to reason) },
            uiDispatcher = { action -> action() }
        )
        client!!.connect()

        assertTrue(audioLatch.await(5, TimeUnit.SECONDS), "audio barrier frame should be delivered")

        assertTrue(states.contains("LISTENING"), "the flag-less STATE frame should still surface its state")
        assertTrue(
            responses.contains(Triple("all good", null, true)),
            "the action-less RESPONSE should route with a null action and handled=true"
        )
        assertTrue(sttStatuses.isEmpty(), "no STT availability change should fire for these frames")
        assertTrue(ttsStatuses.isEmpty(), "no TTS availability change should fire for these frames")
    }

    private fun MockWebServer.wsUrl(path: String): String {
        return url(path).toString().replaceFirst("http://", "ws://")
    }
}
