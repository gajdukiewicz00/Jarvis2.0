package org.jarvis.desktop.e2e.voice

import javafx.scene.Node
import javafx.scene.control.Button
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.voice.VoiceHelpView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * True headless-UI E2E journeys for the Voice command catalog ([VoiceHelpView]).
 * Drives the real Refresh button against a [MockWebServer] and asserts both the
 * rendered category/command cards / status text and the backend request. Also
 * verifies the non-modal "Open voice control" button invokes its callback.
 *
 * [VoiceHelpView] does no I/O in `init {}` (only a placeholder), so construction
 * is safe headlessly; the `/api/v1/voice/help` fetch fires only from Refresh.
 */
class VoiceHelpViewE2eTest {

    private fun buttonNamed(root: Node, text: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == text }

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `refresh renders the grouped command catalog`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { VoiceHelpView(E2eFx.apiClientFor(server), onOpenVoiceControl = {}) }

            server.enqueue(
                json(
                    """
                    {
                      "categories": [
                        {"name": "Audio", "commands": [
                          {"command": "включи музыку", "description": "Play music"}
                        ]},
                        {"name": "Memory", "commands": [
                          {"command": "запомни это", "description": "Remember this"}
                        ]}
                      ]
                    }
                    """.trimIndent()
                )
            )
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "catalog rendered") {
                E2eFx.hasText(view, "включи музыку") && E2eFx.hasText(view, "Ready")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Audio"), "category name rendered")
                assertTrue(E2eFx.hasText(view, "Memory"), "second category rendered")
                assertTrue(E2eFx.hasText(view, "запомни это"), "second command phrase rendered")
                assertTrue(E2eFx.hasText(view, "Play music"), "command description rendered")
                assertTrue(E2eFx.hasText(view, "2 command(s) across 2 group(s)"), "count summary rendered")
            }

            val req = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", req.method)
            assertTrue(req.path!!.endsWith("/api/v1/voice/help"), "path was ${req.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty catalog renders the empty state`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { VoiceHelpView(E2eFx.apiClientFor(server), onOpenVoiceControl = {}) }

            server.enqueue(json("[]"))
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "empty state shown") {
                E2eFx.hasText(view, "Empty") &&
                    E2eFx.hasText(view, "responded but contained no commands")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `backend 500 surfaces an unavailable state`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { VoiceHelpView(E2eFx.apiClientFor(server), onOpenVoiceControl = {}) }

            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            E2eFx.onFx { buttonNamed(view, "Refresh").fire() }

            E2eFx.waitForFx(description = "unavailable state shown") {
                E2eFx.hasText(view, "Unavailable")
            }
            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view, "Каталог голосовых команд временно недоступен"),
                    "localized error placeholder rendered"
                )
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `open voice control button invokes its callback`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.start()
        try {
            val opened = AtomicBoolean(false)
            val view = E2eFx.onFx {
                VoiceHelpView(E2eFx.apiClientFor(server), onOpenVoiceControl = { opened.set(true) })
            }

            E2eFx.onFx { buttonNamed(view, "Open voice control & diagnostics").fire() }

            E2eFx.waitForFx(description = "callback invoked") { opened.get() }
            assertTrue(opened.get(), "onOpenVoiceControl callback fired")
        } finally {
            server.shutdown()
        }
    }
}
