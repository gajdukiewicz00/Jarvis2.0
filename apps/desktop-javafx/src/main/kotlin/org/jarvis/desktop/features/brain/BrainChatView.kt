package org.jarvis.desktop.features.brain

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.features.common.ShellPanelSupport
import org.jarvis.desktop.shell.ShellRouteContent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Brain / AI chat panel — lets the operator talk to the 14B brain directly
 * through `POST /api/v1/llm/chat`. Conversation history is kept in-memory and
 * rendered immutably (a new list is built on each turn).
 */
class BrainChatView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = BrainChatReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-brain").apply { isDaemon = true }
    }
    private val sendInFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Brain")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "Direct line to the local Qwen3-14B brain via /api/v1/llm/chat. Ask anything; replies run on the on-device GPU."
    )

    private val transcript = VBox(12.0)
    private val promptField = TextArea().apply {
        promptText = "Ask the brain… (Ctrl+Enter to send)"
        isWrapText = true
        prefRowCount = 3
    }
    private val sendButton = Button("Send").apply {
        styleClass += "shell-action-button"
        setOnAction { send() }
    }
    private val clearButton = Button("Clear").apply {
        styleClass += "shell-action-button-danger"
        setOnAction { clearTranscript() }
    }

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-brain-view"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
        promptField.setOnKeyPressed { event ->
            if (event.isControlDown && event.code.name == "ENTER") {
                send()
            }
        }
        renderEmpty()
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(6.0).apply {
                children += Label("Brain / AI Chat").apply { styleClass += "shell-page-title" }
                children += Label("Converse with the 14B brain in natural language.").apply {
                    styleClass += "shell-page-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += statusPill
        }

        val transcriptCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Conversation")
            children += statusLabel
            children += transcript
        }

        val composer = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Message")
            children += promptField
            children += HBox(12.0, sendButton, clearButton).apply { alignment = Pos.CENTER_LEFT }
        }

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, transcriptCard, composer)
        }
    }

    private fun send() {
        val prompt = promptField.text?.trim().orEmpty()
        if (prompt.isBlank()) {
            statusPill.text = "Input needed"
            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
            statusLabel.text = "Type a message before sending."
            return
        }
        if (!sendInFlight.compareAndSet(false, true)) {
            return
        }

        appendBubble("You", prompt, isUser = true)
        promptField.clear()
        sendButton.isDisable = true
        statusPill.text = "Thinking"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "The 14B brain is generating a reply…"

        worker.execute {
            try {
                val reply = readModel.chat(prompt)
                Platform.runLater {
                    appendBubble(reply.model ?: "Brain", reply.message, isUser = false)
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    statusLabel.text = reply.model?.let { "Reply from $it." }
                        ?: "Reply received from the brain."
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Error"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Brain chat request failed."
                    appendBubble(
                        "Brain",
                        "Не удалось получить ответ. ${e.message ?: "Unknown error"}",
                        isUser = false
                    )
                }
            } finally {
                sendInFlight.set(false)
                Platform.runLater { sendButton.isDisable = false }
            }
        }
    }

    private fun appendBubble(speaker: String, text: String, isUser: Boolean) {
        if (transcript.children.size == 1 && transcript.children[0].styleClass.contains("shell-placeholder")) {
            transcript.children.clear()
        }
        val bubble = VBox(4.0).apply {
            styleClass += "shell-section-card"
            styleClass += if (isUser) "brain-bubble-user" else "brain-bubble-assistant"
            children += Label(speaker).apply {
                styleClass += if (isUser) "shell-status-tone-info" else "shell-status-tone-success"
                styleClass += "shell-section-title"
            }
            children += Label(text).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
        }
        transcript.children += bubble
    }

    private fun clearTranscript() {
        renderEmpty()
        statusPill.text = "Brain"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-muted")
        statusLabel.text = "Conversation cleared."
    }

    private fun renderEmpty() {
        transcript.children.setAll(
            VBox(6.0).apply {
                styleClass.addAll("shell-section-card", "shell-placeholder")
                children += Label("Начни разговор — задай вопрос своему ассистенту.").apply {
                    styleClass += "shell-placeholder-body"
                    isWrapText = true
                }
            }
        )
    }
}
