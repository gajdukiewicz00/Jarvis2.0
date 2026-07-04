package org.jarvis.desktop.features.voice

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
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
 * Voice command catalog panel — "ты можешь сказать…".
 *
 * Fetches the live spoken-command catalog from `GET /api/v1/voice/help` so the
 * operator can discover everything they can say, grouped by category. A button
 * jumps to the full Voice control + diagnostics screen.
 */
class VoiceHelpView(
    apiClient: ApiClient,
    private val onOpenVoiceControl: () -> Unit
) : ScrollPane(), ShellRouteContent {
    private val readModel = VoiceHelpReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-voice-help").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Voice")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "Everything Jarvis understands by voice, pulled live from the gateway so it always matches the running build."
    )
    private val catalogContainer = VBox(16.0)
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { refresh() }
    }
    private val openVoiceButton = Button("Open voice control & diagnostics").apply {
        styleClass += "shell-action-button"
        setOnAction { onOpenVoiceControl() }
    }

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-voice-help-view"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
        renderPlaceholder("Refresh to load the live voice command catalog.")
    }

    override fun onRouteActivated() {
        refresh()
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(6.0).apply {
                children += Label("Voice — Ты можешь сказать").apply { styleClass += "shell-page-title" }
                children += Label("Discover every spoken command, live from /api/v1/voice/help.").apply {
                    styleClass += "shell-page-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += statusPill
            children += refreshButton
        }

        val intro = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += statusLabel
            children += HBox(12.0, openVoiceButton).apply { alignment = Pos.CENTER_LEFT }
        }

        val catalogCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Command catalog")
            children += catalogContainer
        }

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, intro, catalogCard)
        }
    }

    private fun refresh() {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        refreshButton.isDisable = true
        statusPill.text = "Loading"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")

        worker.execute {
            try {
                val categories = readModel.helpCatalog()
                Platform.runLater {
                    if (categories.isEmpty()) {
                        statusPill.text = "Empty"
                        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
                        statusLabel.text = "The voice help catalog responded but contained no commands."
                        renderPlaceholder("No commands returned by /api/v1/voice/help.")
                    } else {
                        val total = categories.sumOf { it.commands.size }
                        statusPill.text = "Ready"
                        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                        statusLabel.text = "$total command(s) across ${categories.size} group(s)."
                        catalogContainer.children.setAll(categories.map(::categoryCard))
                    }
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Voice help request failed."
                    renderPlaceholder("Каталог голосовых команд временно недоступен.\n${e.message ?: "Unknown error"}")
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { refreshButton.isDisable = false }
            }
        }
    }

    private fun categoryCard(category: VoiceHelpReadModel.Category): Node {
        return VBox(8.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle(category.name)
            category.commands.forEach { command ->
                children += VBox(2.0).apply {
                    children += Label("«${command.phrase}»").apply {
                        styleClass += "shell-section-title"
                        isWrapText = true
                    }
                    if (command.description.isNotBlank()) {
                        children += Label(command.description).apply {
                            styleClass += "shell-section-subtitle"
                            isWrapText = true
                        }
                    }
                }
            }
        }
    }

    private fun renderPlaceholder(message: String) {
        catalogContainer.children.setAll(
            VBox(6.0).apply {
                styleClass.addAll("shell-section-card", "shell-placeholder")
                children += Label(message).apply {
                    styleClass += "shell-placeholder-body"
                    isWrapText = true
                }
            }
        )
    }
}
