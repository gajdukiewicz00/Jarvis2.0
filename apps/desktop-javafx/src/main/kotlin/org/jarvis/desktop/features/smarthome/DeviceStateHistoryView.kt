package org.jarvis.desktop.features.smarthome

import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.features.common.ShellPanelSupport
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "State history" section embedded in the Smart Home route — a bounded,
 * most-recent-first audit trail of state changes for one device, scoped to
 * the signed-in owner.
 *
 * Wires: `GET /api/v1/smarthome/devices/{deviceId}/state-history?limit=`
 *
 * Embedded as a plain [Node] (not a [org.jarvis.desktop.shell.ShellRouteContent])
 * — the containing [SmartHomeView] owns the shell route lifecycle and forwards
 * [shutdown] into this section. Unlike [ScenesView] / [AutomationsView], this
 * panel has no useful default query, so it does not auto-load on route
 * activation — the owner types a device id and loads history explicitly.
 */
class DeviceStateHistoryView(
    apiClient: ApiClient
) : VBox(12.0) {
    private val readModel = DeviceStateHistoryReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-state-history").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("History")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "Query the persisted state-change audit trail for a single device."
    )

    private val deviceIdField = TextField().apply {
        promptText = "Device id (e.g. kitchen-light)"
        setOnAction { load() }
    }
    private val limitField = TextField(DeviceStateHistoryReadModel.DEFAULT_LIMIT.toString()).apply {
        promptText = "Limit"
        prefColumnCount = 4
        setOnAction { load() }
    }
    private val loadButton = Button("Load history").apply {
        styleClass += "shell-action-button"
        setOnAction { load() }
    }

    private val historyContainer = VBox(10.0)

    init {
        styleClass += "shell-section-card"
        children += HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += ShellPanelSupport.sectionTitle("Device state history")
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += statusPill
        }
        children += statusLabel
        children += HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(deviceIdField, Priority.ALWAYS)
            deviceIdField.maxWidth = Double.MAX_VALUE
            children.addAll(deviceIdField, Label("Limit"), limitField, loadButton)
        }
        children += historyContainer
        renderPlaceholder("Enter a device id above and load its history.")
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    private fun load() {
        val deviceId = deviceIdField.text?.trim().orEmpty()
        if (deviceId.isBlank()) {
            statusPill.text = "Input needed"
            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
            statusLabel.text = "A device id is required."
            return
        }
        val limit = limitField.text?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DeviceStateHistoryReadModel.DEFAULT_LIMIT
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        loadButton.isDisable = true
        statusPill.text = "Loading"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "Loading history for \"$deviceId\"…"

        worker.execute {
            try {
                val entries = readModel.history(deviceId, limit)
                Platform.runLater {
                    renderEntries(entries)
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    statusLabel.text = "${entries.size} entr${if (entries.size == 1) "y" else "ies"} for \"$deviceId\"."
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "State-history request failed."
                    renderPlaceholder("Unable to load history for \"$deviceId\".\n${e.message ?: "Unknown error"}")
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { loadButton.isDisable = false }
            }
        }
    }

    private fun renderEntries(entries: List<DeviceStateHistoryReadModel.HistoryEntry>) {
        if (entries.isEmpty()) {
            renderPlaceholder("No recorded state history for this device yet.")
            return
        }
        historyContainer.children.setAll(entries.map(::entryCard))
    }

    private fun entryCard(entry: DeviceStateHistoryReadModel.HistoryEntry): Node {
        return VBox(6.0).apply {
            styleClass.addAll("shell-section-card", "state-history-entry-card")
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label(entry.action).apply { styleClass += "shell-section-title" }
                children += Label(if (entry.success) "Success" else "Failed").apply {
                    styleClass += "shell-status-pill"
                    ShellPanelSupport.applyTone(
                        this,
                        if (entry.success) "shell-status-tone-success" else "shell-status-tone-error"
                    )
                }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                children += Label(entry.recordedAt ?: "unknown time").apply { styleClass += "shell-section-subtitle" }
            }
            entry.payload?.takeIf { it.isNotBlank() }?.let { payload ->
                children += Label("Payload: $payload").apply {
                    styleClass += "shell-section-subtitle"
                    isWrapText = true
                }
            }
            children += Label("State: ${summarize(entry.stateJson)}").apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
        }
    }

    private fun summarize(stateJson: String): String {
        val maxLength = 200
        return if (stateJson.length > maxLength) stateJson.take(maxLength) + "…" else stateJson
    }

    private fun renderPlaceholder(message: String) {
        historyContainer.children.setAll(
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
