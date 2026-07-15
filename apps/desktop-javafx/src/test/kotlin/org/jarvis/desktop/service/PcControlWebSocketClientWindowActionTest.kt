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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies the new [PcControlWebSocketClient.handlePcAction] routing:
 *  - RESTORE_WINDOWS dispatches to [SystemControlService.restoreWindows] (asserted via the exact
 *    wmctrl argv it runs, captured through the SystemControlService test seam).
 *  - A failed OPEN_APP propagates the coded [AppOpenException] message into the negative ACK so the
 *    orchestrator can map it to a voice failureReason.
 *
 * Both use a real client over a MockWebServer with an immediate uiDispatcher; only the OS command
 * layer and the app index are stubbed via SystemControlService seams.
 */
class PcControlWebSocketClientWindowActionTest {

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

    private fun connectWith(
        systemControl: SystemControlService,
        onServerOpen: (WebSocket) -> Unit,
        onAck: (String) -> Unit,
    ): CountDownLatch {
        val openLatch = CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        openLatch.countDown()
                        onServerOpen(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val type = Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
                        if (type == "ACK") onAck(text)
                    }
                })
            }
        }
        server.start()
        client = PcControlWebSocketClient(
            urlProvider = { server.url("/ws/pc-control").toString().replaceFirst("http://", "ws://") },
            systemControl = systemControl,
            onStatusChange = {},
            uiDispatcher = { action -> action() },
        )
        client!!.connect()
        return openLatch
    }

    @Test
    @DisplayName("RESTORE_WINDOWS dispatches to SystemControlService.restoreWindows (wmctrl -k off)")
    fun restoreWindowsDispatches() {
        val captured = CopyOnWriteArrayList<List<String>>()
        val systemControl = SystemControlService().apply {
            commandExecutorOverride = { argv -> captured.add(argv); 0 to "" }
        }
        val ack = AtomicReference<String>()
        val ackLatch = CountDownLatch(1)

        val openLatch = connectWith(
            systemControl = systemControl,
            onServerOpen = { ws ->
                ws.send(
                    buildJsonObject {
                        put("type", "PC_ACTION")
                        put("action", "RESTORE_WINDOWS")
                        put("requestId", "req-restore")
                    }.toString()
                )
            },
            onAck = { text -> ack.set(text); ackLatch.countDown() },
        )

        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "server should accept the upgrade")
        assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "RESTORE_WINDOWS must be acknowledged")
        assertTrue(
            captured.contains(listOf("wmctrl", "-k", "off")),
            "RESTORE_WINDOWS must run wmctrl -k off, ran: $captured",
        )
        val ackText = ack.get()
        assertTrue(ackText.contains("\"success\":true"), "restore should succeed: $ackText")
        assertTrue(ackText.contains("req-restore"))
    }

    @Test
    @DisplayName("OPEN_APP failure ack carries the coded APP_CLARIFY message")
    fun openAppFailureAckCarriesCodedMessage() {
        val systemControl = SystemControlService().apply {
            appOpenerOverride = object : SystemControlService.AppOpener {
                override fun resolve(query: String): AppResolution =
                    AppResolution.Clarify(
                        candidates = listOf(
                            AppEntry(
                                id = "code",
                                name = "VS Code",
                                exec = "",
                                source = AppSource.DESKTOP,
                                launchMethod = LaunchMethod.GTK_LAUNCH,
                            ),
                        ),
                        confidences = listOf(0.70),
                    )

                override fun launchCommand(entry: AppEntry): List<String> = listOf("gtk-launch", entry.id)
            }
        }
        val ack = AtomicReference<String>()
        val ackLatch = CountDownLatch(1)

        val openLatch = connectWith(
            systemControl = systemControl,
            onServerOpen = { ws ->
                ws.send(
                    buildJsonObject {
                        put("type", "PC_ACTION")
                        put("action", "OPEN_APP")
                        put("requestId", "req-open")
                        put("params", buildJsonObject { put("app", "some-unknown-app") })
                    }.toString()
                )
            },
            onAck = { text -> ack.set(text); ackLatch.countDown() },
        )

        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "server should accept the upgrade")
        assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "OPEN_APP must be acknowledged")
        val ackText = ack.get()
        assertTrue(ackText.contains("\"success\":false"), "ambiguous open must NACK: $ackText")
        assertTrue(ackText.contains("APP_CLARIFY|VS Code"), "ack must carry the coded message: $ackText")
    }
}
