package org.jarvis.desktop.ui.tabs

import javafx.application.Platform
import javafx.scene.control.Label
import javafx.scene.control.Button
import javafx.scene.control.Tab
import javafx.scene.control.TextArea
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.model.VoiceUxStatus
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HomeTab(
    private val runtimeMonitor: DesktopRuntimeMonitor,
    private val onRefreshRuntime: () -> Unit = {}
) {
    val tab = Tab("Home")
    private val overallLabel = Label()
    private val backendLabel = Label()
    private val voiceLabel = Label()
    private val pcControlLabel = Label()
    private val lastUpdatedLabel = Label()
    private val recentEventsArea = TextArea()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    init {
        val content = VBox(12.0).apply {
            style = "-fx-padding: 18;"
        }

        content.children.add(Label("Jarvis Home").apply {
            style = "-fx-font-size: 20px; -fx-font-weight: bold;"
        })

        content.children.add(Label(
            "Signed in as ${TokenManager.getUsername() ?: "unknown"} • API ${AppConfig.apiGatewayBaseUrl}"
        ).apply {
            style = "-fx-text-fill: #555;"
        })

        overallLabel.style = "-fx-font-size: 15px; -fx-font-weight: bold;"
        content.children.add(overallLabel)

        val grid = GridPane().apply {
            hgap = 12.0
            vgap = 10.0
        }
        grid.add(Label("Runtime"), 0, 0)
        grid.add(backendLabel, 1, 0)
        grid.add(Label("Voice"), 0, 1)
        grid.add(voiceLabel, 1, 1)
        grid.add(Label("Desktop actions"), 0, 2)
        grid.add(pcControlLabel, 1, 2)
        grid.add(Label("Last update"), 0, 3)
        grid.add(lastUpdatedLabel, 1, 3)
        content.children.add(grid)

        val header = HBox(10.0).apply {
            children.addAll(
                Label("Recent assistant activity").apply {
                    style = "-fx-font-size: 14px; -fx-font-weight: bold;"
                },
                Button("Refresh runtime").apply {
                    setOnAction { onRefreshRuntime() }
                }
            )
        }
        content.children.add(header)

        recentEventsArea.isEditable = false
        recentEventsArea.isWrapText = true
        recentEventsArea.prefRowCount = 12
        VBox.setVgrow(recentEventsArea, Priority.ALWAYS)
        content.children.add(recentEventsArea)

        tab.content = content
        tab.isClosable = false

        runtimeMonitor.addListener { snapshot ->
            Platform.runLater { render(snapshot) }
        }
    }

    private fun render(snapshot: DesktopRuntimeMonitor.Snapshot) {
        overallLabel.text = when (snapshot.overallState()) {
            DesktopRuntimeMonitor.ConnectionState.CONNECTED -> "Ready: runtime, voice, and desktop actions are connected."
            DesktopRuntimeMonitor.ConnectionState.CONNECTING -> "Starting up: Jarvis is still connecting."
            DesktopRuntimeMonitor.ConnectionState.DEGRADED -> "Degraded: Jarvis is reachable, but one channel needs attention."
            DesktopRuntimeMonitor.ConnectionState.DISCONNECTED -> "Disconnected: backend is not currently reachable."
            DesktopRuntimeMonitor.ConnectionState.ERROR -> "Attention needed: one or more assistant channels failed."
            DesktopRuntimeMonitor.ConnectionState.UNKNOWN -> "Checking Jarvis runtime..."
        }

        overallLabel.style = when (snapshot.overallState()) {
            DesktopRuntimeMonitor.ConnectionState.CONNECTED -> "-fx-text-fill: #2e7d32; -fx-font-size: 15px; -fx-font-weight: bold;"
            DesktopRuntimeMonitor.ConnectionState.CONNECTING -> "-fx-text-fill: #1565c0; -fx-font-size: 15px; -fx-font-weight: bold;"
            DesktopRuntimeMonitor.ConnectionState.DEGRADED -> "-fx-text-fill: #ef6c00; -fx-font-size: 15px; -fx-font-weight: bold;"
            DesktopRuntimeMonitor.ConnectionState.DISCONNECTED,
            DesktopRuntimeMonitor.ConnectionState.ERROR -> "-fx-text-fill: #c62828; -fx-font-size: 15px; -fx-font-weight: bold;"
            DesktopRuntimeMonitor.ConnectionState.UNKNOWN -> "-fx-text-fill: #555; -fx-font-size: 15px; -fx-font-weight: bold;"
        }

        backendLabel.text = describe(snapshot.backend)
        voiceLabel.text = describeVoice(snapshot)
        pcControlLabel.text = describe(snapshot.pcControl)
        lastUpdatedLabel.text = listOf(snapshot.backend, snapshot.voice, snapshot.pcControl)
            .maxByOrNull { it.updatedAt }
            ?.updatedAt
            ?.let(timeFormatter::format)
            ?: "n/a"

        recentEventsArea.text = if (snapshot.events.isEmpty()) {
            "No assistant activity yet."
        } else {
            snapshot.events.joinToString("\n\n") {
                val timestamp = timeFormatter.format(it.timestamp)
                "[$timestamp] ${it.source}: ${it.title}${if (it.details.isNotBlank()) "\n${it.details}" else ""}"
            }
        }
    }

    private fun describeVoice(snapshot: DesktopRuntimeMonitor.Snapshot): String {
        val vr = snapshot.voiceRuntime ?: return describe(snapshot.voice)
        val status = VoiceUxStatus.compute(vr)
        val parts = mutableListOf(status.headline)
        vr.inputDevice?.let { parts += "mic: ${it.name}" }
        status.guidance?.let { parts += it }
        return parts.joinToString(" • ")
    }

    private fun describe(status: DesktopRuntimeMonitor.ConnectionStatus): String {
        return "${status.state.name.lowercase().replaceFirstChar(Char::titlecase)} • ${status.detail}"
    }
}
