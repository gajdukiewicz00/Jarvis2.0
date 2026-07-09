package org.jarvis.desktop.e2e.proactive

import javafx.scene.Node
import javafx.scene.control.Button
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.proactive.ProactiveView
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * True headless-UI E2E journeys for the Proactive panel ([ProactiveView]).
 * Drives the real Refresh button against a [MockWebServer] and asserts both the
 * rendered observation cards / status text and the honest degraded state the
 * panel shows when the host-side proactive feed is not exposed through the
 * gateway (every candidate route 404s).
 *
 * [ProactiveView] does no I/O in `init {}` (it only renders a placeholder), so
 * construction is safe headlessly; the fan-out over candidate routes fires only
 * from the Refresh handler, which each test invokes explicitly.
 */
class ProactiveViewE2eTest {

    private fun buttonNamed(root: Node, text: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == text }

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `refresh renders recent observations and a ready status`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return if (path.contains("/proactive/observations")) {
                    json(
                        """
                        [
                          {"title": "High CPU on jarvis-llm", "detail": "Sustained 82% for 5m", "timestamp": "12:00:01"},
                          {"title": "Idle window", "detail": "No active window for 20m", "timestamp": "12:05:00"}
                        ]
                        """.trimIndent()
                    )
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val view = E2eFx.onFx { ProactiveView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "observation cards rendered") {
                E2eFx.hasText(view, "High CPU on jarvis-llm") && E2eFx.hasText(view, "Ready")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Idle window"), "second observation rendered")
                assertTrue(E2eFx.hasText(view, "Sustained 82% for 5m"), "observation detail rendered")
                assertTrue(E2eFx.hasText(view, "2 recent observation(s)"), "count summary rendered")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty available feed renders the no-observations placeholder`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return if (path.contains("/proactive/observations")) json("[]")
                else MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val view = E2eFx.onFx { ProactiveView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "empty-state placeholder shown") {
                E2eFx.hasText(view, "No proactive observations recorded yet") &&
                    E2eFx.hasText(view, "Ready")
            }
            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view, "Nothing to show yet"),
                    "empty placeholder body rendered"
                )
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `all routes 404 renders the honest partial-feature state`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(404).setBody("not here")
        }
        server.start()
        try {
            val view = E2eFx.onFx { ProactiveView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "partial-feature state shown") {
                E2eFx.hasText(view, "Partial feature")
            }
            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view, "not deployed"),
                    "honest host-side-only explanation rendered"
                )
            }
        } finally {
            server.shutdown()
        }
    }
}
