package org.jarvis.desktop.service

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
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
 * Covers the inbound-message routing of [PcControlWebSocketClient.onMessage]
 * (PING -> PONG round-trip, ACK, unknown type, binary), plus the two
 * process-free failure branches of handlePcAction (an unknown action and a
 * required-parameter-missing action) which both emit a negative ACK without
 * ever invoking SystemControlService / spawning an OS process. Also covers the
 * already-connected connect() guard and the onClosing / onClosed / disconnect
 * state transitions.
 */
class PcControlWebSocketClientMessageTest {

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
        client = null
        TokenManager.clearTokens()
        settingsStore.save(originalSettings)
        AppConfig.reload()
        runCatching { server.shutdown() }
    }

    @Test
    @DisplayName("PING is answered with PONG and PC_ACTION failures emit negative ACKs")
    fun routesPingAckAndActionFailures() {
        val clientMessages = CopyOnWriteArrayList<String>()
        val statuses = CopyOnWriteArrayList<String>()
        // IDENTIFY + PONG + 2 negative ACKs = 4 client->server text frames.
        val messagesLatch = CountDownLatch(4)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"PING"}""")
                webSocket.send("""{"type":"ACK"}""")
                webSocket.send("""{"type":"MYSTERY"}""")
                webSocket.send(byteArrayOf(0, 1, 2).toByteString())
                webSocket.send("""{"type":"PC_ACTION","action":"TOTALLY_UNKNOWN","requestId":"req-unknown"}""")
                webSocket.send("""{"type":"PC_ACTION","action":"OPEN_APP","requestId":"req-openapp"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                clientMessages += text
                messagesLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // PC control never sends binary to the server; ignore.
            }
        }))
        server.start()

        AppConfig.saveSettings(
            apiGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
            locale = Locale.ENGLISH,
            manualEndpointOverride = true
        )

        val connectedLatch = CountDownLatch(1)
        client = PcControlWebSocketClient(
            urlProvider = { server.wsUrl("/ws/pc-control") },
            systemControl = SystemControlService(),
            onStatusChange = { status ->
                statuses += status
                if (status == "Connected") connectedLatch.countDown()
            },
            uiDispatcher = { action -> action() }
        )
        client!!.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "pc-control websocket should connect")
        assertTrue(messagesLatch.await(5, TimeUnit.SECONDS), "client should answer ping and ack the actions")

        assertTrue(clientMessages.any { it.contains("IDENTIFY") }, "IDENTIFY on open")
        assertTrue(clientMessages.any { it.contains("PONG") }, "PONG reply to PING")

        val acks = clientMessages.filter { it.contains("\"ACK\"") }
        assertTrue(
            acks.any { it.contains("req-unknown") && it.contains("\"success\":false") },
            "unknown action should be NACKed"
        )
        assertTrue(
            acks.any { it.contains("req-openapp") && it.contains("\"success\":false") },
            "missing-parameter action should be NACKed"
        )
        assertTrue(statuses.any { it.startsWith("✗") }, "a failure status should be published")
    }

    @Test
    @DisplayName("connect is a no-op while already connected")
    fun connectIsNoOpWhenAlreadyConnected() {
        client = PcControlWebSocketClient(
            urlProvider = { "ws://127.0.0.1:1/ws/pc-control" },
            systemControl = SystemControlService(),
            uiDispatcher = { action -> action() }
        )
        connectedField().setBoolean(client, true)

        // Already connected: connect() must return before touching config/status.
        client!!.connect()
        assertTrue(client!!.isConnected())
    }

    @Test
    @DisplayName("onClosing/onClosed publish disconnected status without reconnecting past the cap")
    fun closeCallbacksPublishStatus() {
        val statuses = CopyOnWriteArrayList<String>()
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

        client = PcControlWebSocketClient(
            urlProvider = { server.wsUrl("/ws/pc-control") },
            systemControl = SystemControlService(),
            onStatusChange = { status ->
                statuses += status
                if (status == "Connected") connectedLatch.countDown()
            },
            uiDispatcher = { action -> action() }
        )
        client!!.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "pc-control websocket should connect")

        // Max out reconnect attempts so onClosed(non-1000) does NOT spawn a reconnect thread.
        reconnectAttemptsField().setInt(client, 10)
        val liveSocket = socketField().get(client) as WebSocket

        client!!.onClosing(liveSocket, 1001, "server going away")
        assertFalse(client!!.isConnected())
        assertTrue(statuses.contains("Disconnected"))

        statuses.clear()
        client!!.onClosed(liveSocket, 1011, "internal error")
        assertTrue(statuses.any { it.startsWith("Connection failed:") }, "non-1000 close surfaces failure")
    }

    @Test
    @DisplayName("disconnect closes the socket and reports Disconnected")
    fun disconnectReportsDisconnected() {
        val statuses = CopyOnWriteArrayList<String>()
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

        client = PcControlWebSocketClient(
            urlProvider = { server.wsUrl("/ws/pc-control") },
            systemControl = SystemControlService(),
            onStatusChange = { status ->
                statuses += status
                if (status == "Connected") connectedLatch.countDown()
            },
            uiDispatcher = { action -> action() }
        )
        client!!.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "pc-control websocket should connect")

        client!!.disconnect()
        assertFalse(client!!.isConnected())
        assertTrue(statuses.contains("Disconnected"))
    }

    private fun connectedField() = PcControlWebSocketClient::class.java.getDeclaredField("isConnected").apply {
        isAccessible = true
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
