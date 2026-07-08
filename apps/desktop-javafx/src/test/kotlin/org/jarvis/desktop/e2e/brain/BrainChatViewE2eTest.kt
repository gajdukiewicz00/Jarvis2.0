package org.jarvis.desktop.e2e.brain

import javafx.scene.control.Button
import javafx.scene.control.TextArea
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.brain.BrainChatView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TRUE UI end-to-end journeys for the Brain / AI chat panel.
 *
 * Drives the real [BrainChatView] scene graph (TextArea + Send button) against a
 * [MockWebServer] standing in for the api-gateway, then asserts BOTH that the
 * expected `POST /api/v1/llm/chat` reached the backend AND that the visible
 * widget tree reacted (assistant/error bubble + status pill/label).
 */
class BrainChatViewE2eTest {

    /**
     * Locate the Send button by its label among the two shell-action buttons
     * (Send + Clear). Must be called on the FX thread.
     */
    private fun sendButton(view: BrainChatView): Button =
        E2eFx.findAll<Button>(view.content).first { it.text == "Send" }

    private fun promptArea(view: BrainChatView): TextArea =
        E2eFx.find<TextArea>(view.content)!!

    @Test
    fun `happy path - typing a prompt and sending renders the assistant reply bubble`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "choices": [
                        {"message": {"role": "assistant", "content": "Systems nominal, sir."}}
                      ],
                      "model": "qwen3-14b"
                    }
                    """.trimIndent()
                )
        )
        server.start()
        try {
            val view = E2eFx.onFx { BrainChatView(E2eFx.apiClientFor(server)) }

            // Placeholder is shown before any turn.
            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view.content, "Начни разговор"),
                    "empty-state placeholder should be visible before first turn"
                )
            }

            // Type a prompt and fire the real Send button.
            E2eFx.onFx {
                promptArea(view).text = "What is our status?"
                sendButton(view).fire()
            }

            // The user's message bubble renders synchronously on the FX thread.
            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view.content, "What is our status?"),
                    "user bubble should echo the typed prompt"
                )
                assertTrue(E2eFx.hasText(view.content, "Thinking"), "status pill should flip to Thinking")
            }

            // Assistant reply arrives async via worker thread + Platform.runLater.
            E2eFx.waitForFx(description = "assistant reply bubble rendered") {
                E2eFx.hasText(view.content, "Systems nominal, sir.")
            }

            E2eFx.onFx {
                // Reply body + model attribution surfaced in the scene graph.
                assertTrue(E2eFx.hasText(view.content, "Systems nominal, sir."))
                assertTrue(
                    E2eFx.hasText(view.content, "qwen3-14b"),
                    "model name should appear in the reply bubble speaker / status"
                )
                assertTrue(E2eFx.hasText(view.content, "Ready"), "status pill should settle on Ready")
                // Prompt field cleared after send, Send re-enabled.
                assertEquals("", promptArea(view).text)
                assertFalse(sendButton(view).isDisable, "Send button should be re-enabled")
                // Placeholder is gone once a real turn exists.
                assertFalse(E2eFx.hasText(view.content, "Начни разговор"))
            }

            // Backend received the correct chat call with our prompt.
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertTrue(req.path!!.contains("/api/v1/llm/chat"), "path was ${req.path}")
            val body = req.body.readUtf8()
            assertTrue(body.contains("\"role\":\"user\""), "body was $body")
            assertTrue(body.contains("What is our status?"), "prompt should be in the request body")
            assertTrue(body.contains("\"stream\":false"), "stream flag should be false")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `error path - backend 500 shows an error bubble and error status`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"brain offline"}""")
        )
        server.start()
        try {
            val view = E2eFx.onFx { BrainChatView(E2eFx.apiClientFor(server)) }

            E2eFx.onFx {
                promptArea(view).text = "Are you there?"
                sendButton(view).fire()
            }

            // User bubble still renders immediately, even on the failing turn.
            E2eFx.onFx { assertTrue(E2eFx.hasText(view.content, "Are you there?")) }

            // Failure surfaces as an error status pill + Russian error bubble.
            E2eFx.waitForFx(description = "error status + error bubble rendered") {
                E2eFx.hasText(view.content, "Error") && E2eFx.hasText(view.content, "Не удалось получить ответ")
            }

            E2eFx.onFx {
                // ApiClient maps 5xx to a "Server error (500)" message shown in status label.
                assertTrue(
                    E2eFx.hasText(view.content, "Server error (500)"),
                    "status label should show the mapped 5xx error: ${E2eFx.visibleText(view.content)}"
                )
                // Send button recovered so the operator can retry.
                assertFalse(sendButton(view).isDisable, "Send button should be re-enabled after failure")
            }

            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertTrue(req.path!!.contains("/api/v1/llm/chat"), "path was ${req.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `edge path - sending a blank prompt warns and makes no backend call`() {
        val server = MockWebServer()
        // No response enqueued: any request would hang / fail, proving none is sent.
        server.start()
        try {
            val view = E2eFx.onFx { BrainChatView(E2eFx.apiClientFor(server)) }

            // Whitespace-only prompt should be rejected client-side.
            E2eFx.onFx {
                promptArea(view).text = "   "
                sendButton(view).fire()
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view.content, "Input needed"), "status pill should warn on blank input")
                assertTrue(
                    E2eFx.hasText(view.content, "Type a message before sending"),
                    "status label should prompt for input: ${E2eFx.visibleText(view.content)}"
                )
                // No bubbles were appended — placeholder remains.
                assertTrue(E2eFx.hasText(view.content, "Начни разговор"), "empty-state placeholder should remain")
            }

            // Confirm the backend never received a request.
            assertEquals(0, server.requestCount, "blank send must not hit the backend")
        } finally {
            server.shutdown()
        }
    }
}
