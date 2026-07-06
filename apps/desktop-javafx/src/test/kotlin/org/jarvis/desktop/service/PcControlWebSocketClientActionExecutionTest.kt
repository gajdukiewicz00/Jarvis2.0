package org.jarvis.desktop.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.auth.TokenManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression coverage for finding #32: handlePcAction() used to run the entire
 * action-execution block (including the blocking SystemControlService/ProcessBuilder
 * calls) inside `uiDispatcher { ... }`. Production wires uiDispatcher to
 * Platform.runLater, so real OS command execution happened on the JavaFX
 * Application Thread and could freeze the desktop shell.
 *
 * This test simulates a stalled/blocked UI thread by handing the client a
 * uiDispatcher that never invokes the runnable it is given (i.e. the FX event
 * queue is never drained). Before the fix, that meant the whole action body -
 * including sending the ACK back to the orchestrator - never ran either, since
 * it was nested inside the very callback the stalled dispatcher drops. After
 * the fix, action execution runs on a dedicated background executor
 * independent of uiDispatcher, so the ACK still arrives promptly.
 */
class PcControlWebSocketClientActionExecutionTest {

    private val server = MockWebServer()
    private var client: PcControlWebSocketClient? = null

    @BeforeEach
    fun setUp() {
        TokenManager.clearTokens()
    }

    @AfterEach
    fun tearDown() {
        client?.disconnect()
        client = null
        TokenManager.clearTokens()
        runCatching { server.shutdown() }
    }

    @Test
    @DisplayName("PC action ACK is not blocked by a stalled/never-drained UI dispatcher")
    fun ackIsSentEvenWhenUiDispatcherNeverRunsTheCallback() {
        val openLatch = CountDownLatch(1)
        val ackLatch = CountDownLatch(1)
        val serverSocketRef = AtomicReference<WebSocket>()
        val ackMessage = AtomicReference<String>()

        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocketRef.set(webSocket)
                        openLatch.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val payload = Json.parseToJsonElement(text).jsonObject
                        if (payload["type"]?.jsonPrimitive?.content == "ACK") {
                            ackMessage.set(text)
                            ackLatch.countDown()
                        }
                    }
                })
            }
        }
        server.start()

        client = PcControlWebSocketClient(
            urlProvider = { server.wsUrl("/ws/pc-control") },
            systemControl = SystemControlService(),
            onStatusChange = {},
            // Simulates a stalled JavaFX Application Thread: the runnable handed to
            // uiDispatcher is intentionally never invoked.
            uiDispatcher = { /* never run the action */ }
        )
        client!!.connect()

        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "test websocket server should accept the upgrade")

        val serverSocket = requireNotNull(serverSocketRef.get())
        serverSocket.send(
            buildJsonObject {
                put("type", "PC_ACTION")
                put("action", "BEEP")
                put("requestId", "req-1")
            }.toString()
        )

        assertTrue(
            ackLatch.await(5, TimeUnit.SECONDS),
            "ACK should be sent even though uiDispatcher never drains its queued callback - " +
                "action execution must not depend on the UI thread running"
        )
        assertTrue(ackMessage.get().contains("\"action\":\"BEEP\""))
    }

    private fun MockWebServer.wsUrl(path: String): String {
        return url(path).toString().replaceFirst("http://", "ws://")
    }
}
