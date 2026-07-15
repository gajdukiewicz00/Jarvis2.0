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
 * Covers the remaining process-free failure branches of
 * [PcControlWebSocketClient.handlePcAction]: every required-parameter-missing
 * action (HOTKEY, WINDOW, SCENARIO, OPEN_URL, SYSTEM_COMMAND-missing) and the
 * SYSTEM_COMMAND "unknown command" arm all short-circuit to a negative ACK
 * *before* SystemControlService spawns any OS process. Also covers the
 * action-less PC_ACTION frame, which returns silently without emitting an ACK.
 */
class PcControlWebSocketClientMissingParamTest {

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
    @DisplayName("missing-parameter and unknown-command actions all NACK without running a process")
    fun missingParameterActionsAreNacked() {
        val clientMessages = CopyOnWriteArrayList<String>()
        // IDENTIFY + 6 negative ACKs. The action-less frame emits nothing.
        val messagesLatch = CountDownLatch(7)

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"PC_ACTION","action":"HOTKEY","requestId":"req-hotkey"}""")
                webSocket.send("""{"type":"PC_ACTION","action":"WINDOW","requestId":"req-window"}""")
                webSocket.send("""{"type":"PC_ACTION","action":"SCENARIO","requestId":"req-scenario"}""")
                webSocket.send("""{"type":"PC_ACTION","action":"OPEN_URL","requestId":"req-url"}""")
                webSocket.send("""{"type":"PC_ACTION","action":"SYSTEM_COMMAND","requestId":"req-sys-missing"}""")
                webSocket.send(
                    """{"type":"PC_ACTION","action":"SYSTEM_COMMAND","requestId":"req-sys-unknown","params":{"command":"teleport"}}"""
                )
                // No "action" field: handlePcAction returns before doing anything.
                webSocket.send("""{"type":"PC_ACTION","requestId":"req-noop"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                clientMessages += text
                messagesLatch.countDown()
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
            onStatusChange = { if (it == "Connected") connectedLatch.countDown() },
            uiDispatcher = { action -> action() }
        )
        client!!.connect()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "pc-control websocket should connect")
        assertTrue(messagesLatch.await(5, TimeUnit.SECONDS), "each invalid action should be acknowledged")

        val acks = clientMessages.filter { it.contains("\"ACK\"") }
        listOf(
            "req-hotkey",
            "req-window",
            "req-scenario",
            "req-url",
            "req-sys-missing",
            "req-sys-unknown"
        ).forEach { requestId ->
            assertTrue(
                acks.any { it.contains(requestId) && it.contains("\"success\":false") },
                "$requestId should be NACKed"
            )
        }

        // The action-less frame must not have produced an ACK.
        assertFalse(acks.any { it.contains("req-noop") }, "action-less frame must not be acknowledged")
    }

    private fun MockWebServer.wsUrl(path: String): String {
        return url(path).toString().replaceFirst("http://", "ws://")
    }
}
