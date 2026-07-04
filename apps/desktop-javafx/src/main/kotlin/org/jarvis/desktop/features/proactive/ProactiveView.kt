package org.jarvis.desktop.features.proactive

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
 * Proactive panel — shows recent proactive observations and the awareness-loop
 * state. The proactive loop runs host-side, so when its feed is not exposed
 * through the gateway this panel renders an honest degraded state.
 */
class ProactiveView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = ProactiveReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-proactive").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Proactive")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "Jarvis watches context (active window, time, recent activity) and reasons about what to surface next. Spoken nudges still need speakers."
    )
    private val observationsContainer = VBox(10.0)
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { refresh() }
    }

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-proactive-view"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
        renderPlaceholder("Refresh to load recent proactive observations.")
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
                children += Label("Proactive").apply { styleClass += "shell-page-title" }
                children += Label("What Jarvis noticed and reasoned about.").apply {
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

        val card = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Recent observations")
            children += statusLabel
            children += observationsContainer
        }

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, card)
        }
    }

    private fun refresh() {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        refreshButton.isDisable = true
        statusPill.text = "Checking"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")

        worker.execute {
            val result = readModel.recentObservations()
            Platform.runLater {
                when (result) {
                    is ProactiveReadModel.Result.Available -> {
                        statusPill.text = "Ready"
                        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                        if (result.observations.isEmpty()) {
                            statusLabel.text = "No proactive observations recorded yet."
                            renderPlaceholder("Nothing to show yet — Jarvis hasn't flagged anything.")
                        } else {
                            statusLabel.text = "${result.observations.size} recent observation(s)."
                            observationsContainer.children.setAll(result.observations.map(::observationCard))
                        }
                    }
                    is ProactiveReadModel.Result.Unavailable -> {
                        statusPill.text = "Unavailable"
                        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
                        statusLabel.text = result.reason
                        renderPlaceholder("Проактивный модуль временно недоступен.\n${result.reason}")
                    }
                }
                refreshButton.isDisable = false
                inFlight.set(false)
            }
        }
    }

    private fun observationCard(observation: ProactiveReadModel.Observation): Node {
        return VBox(4.0).apply {
            styleClass += "shell-section-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label(observation.title).apply { styleClass += "shell-section-title" }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                if (observation.timestamp.isNotBlank()) {
                    children += Label(observation.timestamp).apply { styleClass += "shell-section-subtitle" }
                }
            }
            if (observation.detail.isNotBlank()) {
                children += Label(observation.detail).apply {
                    styleClass += "shell-section-subtitle"
                    isWrapText = true
                }
            }
        }
    }

    private fun renderPlaceholder(message: String) {
        observationsContainer.children.setAll(
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
