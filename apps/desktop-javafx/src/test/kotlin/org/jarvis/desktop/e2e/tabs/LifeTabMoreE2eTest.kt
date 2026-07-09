package org.jarvis.desktop.e2e.tabs

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.TextField
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.ui.tabs.LifeTab
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Second-wave headless coverage for [LifeTab] error arms the primary suite
 * skips: the access-denied (403) and connection-refused status branches of
 * `loadExpenses`, plus the add-expense and start-timer failure `catch` blocks
 * that fire when a POST is rejected by the backend.
 */
class LifeTabMoreE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun button(root: Node, label: String): Button =
        requireNotNull(E2eFx.findAll<Button>(root).firstOrNull { it.text == label }) { "button '$label' not found" }

    /** An [ApiClient] pointed at a port where nothing listens — the synchronous open-load fails with a refused connection. */
    private fun deadApiClient(): ApiClient = ApiClient(
        configProvider = {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = "http://127.0.0.1:1",
                apiBaseUrl = "http://127.0.0.1:1/api/v1",
                voiceWebSocketUrl = "ws://127.0.0.1:1/ws/voice",
                pcControlWebSocketUrl = "ws://127.0.0.1:1/ws/pc-control",
                locale = Locale.ENGLISH,
                voiceLanguage = "en-US",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                apiGatewayReason = "life unavailable test",
                usesManualEndpointOverride = true
            )
        }
    )

    @Test
    fun `a refused connection surfaces the server-unavailable status`() {
        val content: Node = E2eFx.onFx { LifeTab(deadApiClient()).tab.content }

        // loadExpenses runs synchronously in the constructor; the refused GET maps
        // to "Connection refused ..." which the tab renders as server-unavailable.
        assertTrue(
            E2eFx.onFx { E2eFx.hasText(content, "Server unavailable") },
            "a refused connection should render the server-unavailable guidance"
        )
    }

    @Test
    fun `a 403 on load degrades to the access-denied status`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(403).setBody("forbidden")
        }
        server.start()
        try {
            val content: Node = E2eFx.onFx { LifeTab(E2eFx.apiClientFor(server)).tab.content }
            assertTrue(
                E2eFx.onFx { E2eFx.hasText(content, "Access denied") },
                "a 403 should map to the access-denied guidance"
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a rejected add-expense POST surfaces the error status`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.method) {
                    "POST" -> MockResponse().setResponseCode(500).setBody("boom")
                    else -> json("[]") // GET expenses on open + reload
                }
        }
        server.start()
        try {
            val content: Node = E2eFx.onFx { LifeTab(E2eFx.apiClientFor(server)).tab.content }

            E2eFx.onFx {
                val amountField = requireNotNull(
                    E2eFx.findAll<TextField>(content).firstOrNull { it.promptText == "Amount (EUR)" }
                ) { "amount field not found" }
                amountField.text = "5.00"
                button(content, "Add Expense").fire()
            }

            E2eFx.waitForFx(description = "add-expense failure status") {
                E2eFx.hasText(content, "Error")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a rejected start-timer POST surfaces the error status`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.method) {
                    "POST" -> MockResponse().setResponseCode(500).setBody("boom")
                    else -> json("[]") // GET expenses on open keeps construction clean
                }
        }
        server.start()
        try {
            val content: Node = E2eFx.onFx { LifeTab(E2eFx.apiClientFor(server)).tab.content }

            E2eFx.onFx { button(content, "Start Timer").fire() }

            E2eFx.waitForFx(description = "start-timer failure status") {
                E2eFx.hasText(content, "Error")
            }
        } finally {
            server.shutdown()
        }
    }
}
