package org.jarvis.desktop.service

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VoiceWebSocketClientStaleCallbackTest {

    private val primaryServer = MockWebServer()
    private val replacementServer = MockWebServer()
    private lateinit var originalConfig: ResolvedDesktopConfig
    private var client: VoiceWebSocketClient? = null

    @BeforeEach
    fun setUp() {
        originalConfig = AppConfig.current()
    }

    @AfterEach
    fun tearDown() {
        client?.disconnect()
        AppConfig.saveSettings(
            apiGatewayBaseUrl = originalConfig.apiGatewayBaseUrl,
            locale = originalConfig.locale,
            manualEndpointOverride = originalConfig.usesManualEndpointOverride
        )
        runCatching { primaryServer.shutdown() }
        runCatching { replacementServer.shutdown() }
    }

    @Test
    fun `stale socket callbacks do not mutate the current live session`() {
        primaryServer.enqueue(wsUpgrade())
        replacementServer.enqueue(wsUpgrade())
        primaryServer.start()
        replacementServer.start()

        val states = CopyOnWriteArrayList<String>()
        val firstConnectedLatch = CountDownLatch(1)
        val secondConnectedLatch = CountDownLatch(1)
        val connectionCount = AtomicInteger(0)
        client = VoiceWebSocketClient(
            urlProvider = { AppConfig.current().voiceWebSocketUrl },
            onStateChange = { state ->
                states += state
                if (state == "CONNECTED") {
                    when (connectionCount.incrementAndGet()) {
                        1 -> firstConnectedLatch.countDown()
                        2 -> secondConnectedLatch.countDown()
                    }
                }
            },
            onTranscript = { _, _, _ -> },
            onResponse = { _, _, _ -> },
            onAudioReceived = {},
            uiDispatcher = { action -> action() }
        )

        AppConfig.saveSettings(
            apiGatewayBaseUrl = primaryServer.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )
        client!!.connect()
        assertTrue(firstConnectedLatch.await(5, TimeUnit.SECONDS), "primary endpoint should connect")

        val oldSocket = socketField().get(client) as WebSocket
        AppConfig.saveSettings(
            apiGatewayBaseUrl = replacementServer.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )

        assertTrue(secondConnectedLatch.await(5, TimeUnit.SECONDS), "replacement endpoint should connect")
        val currentSocket = socketField().get(client) as WebSocket
        assertNotSame(oldSocket, currentSocket)

        val stateCountBeforeStaleCallbacks = states.size
        client!!.onFailure(oldSocket, IllegalStateException("stale socket timeout"), null)
        client!!.onClosed(oldSocket, 1011, "stale close")

        Thread.sleep(250L)

        assertTrue(client!!.isConnected)
        assertEquals(stateCountBeforeStaleCallbacks, states.size)
        assertEquals(2, states.count { it == "CONNECTED" })
        assertTrue(states.none { it.contains("stale socket", ignoreCase = true) })
    }

    private fun socketField() = VoiceWebSocketClient::class.java.getDeclaredField("webSocket").apply {
        isAccessible = true
    }

    private fun wsUpgrade(): MockResponse {
        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Keep the websocket open for the test assertions.
            }
        })
    }
}
