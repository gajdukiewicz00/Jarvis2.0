package org.jarvis.desktop.features.smarthome

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
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
 * "Intent" section embedded in the Smart Home route — a free-text box that
 * resolves a natural-language command (EN or RU, e.g. "turn on the kitchen
 * light") into a device/action plan, and lets the owner execute that plan.
 *
 * Wires:
 *  - resolve intent -> POST /api/v1/smarthome/intent
 *  - execute plan   -> POST /api/v1/smarthome/devices/{id}/action?confirm=
 *    (via [SmartHomeActionReadModel] — locks/doors/garages come back with
 *    `needsConfirmation=true` until the owner confirms).
 *
 * Embedded as a plain [javafx.scene.Node] (not a
 * [org.jarvis.desktop.shell.ShellRouteContent]) — the containing
 * [SmartHomeView] owns the shell route lifecycle and forwards [shutdown].
 */
class IntentView(
    apiClient: ApiClient
) : VBox(12.0) {
    private val readModel = IntentReadModel(apiClient)
    private val actionModel = SmartHomeActionReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-smarthome-intent").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private var lastResolution: IntentReadModel.Resolution? = null

    private val statusPill = ShellPanelSupport.statusPill("Intent")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "Type a natural-language command and resolve it to a device/action plan. Nothing is actuated until you execute it."
    )

    private val utteranceField = TextField().apply {
        promptText = "e.g. \"turn on the kitchen light\" / \"включи свет на кухне\""
        setOnAction { resolveIntent() }
    }
    private val resolveButton = Button("Resolve").apply {
        styleClass += "shell-action-button"
        setOnAction { resolveIntent() }
    }
    private val executeButton = Button("Execute").apply {
        styleClass += "shell-action-button"
        isDisable = true
        setOnAction { beginExecute() }
    }

    private val resultContainer = VBox(6.0)

    init {
        styleClass += "shell-section-card"
        children += HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += ShellPanelSupport.sectionTitle("Intent")
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += statusPill
        }
        children += statusLabel
        children += HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(utteranceField, Priority.ALWAYS)
            utteranceField.maxWidth = Double.MAX_VALUE
            children.addAll(utteranceField, resolveButton, executeButton)
        }
        children += resultContainer
        renderPlaceholder("Resolve a command above to see the planned action.")
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    private fun resolveIntent() {
        val utterance = utteranceField.text?.trim().orEmpty()
        if (utterance.isBlank()) {
            statusPill.text = "Input needed"
            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        setBusy(true)
        statusPill.text = "Resolving"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")

        worker.execute {
            try {
                val resolution = readModel.resolve(utterance)
                Platform.runLater {
                    lastResolution = resolution
                    renderResolution(resolution)
                    executeButton.isDisable = !resolution.isExecutable
                    statusPill.text = resolution.status
                    ShellPanelSupport.applyTone(
                        statusPill,
                        if (resolution.status == "RESOLVED") "shell-status-tone-success" else "shell-status-tone-warning"
                    )
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    renderPlaceholder("Unable to resolve intent.\n${e.message ?: "Unknown error"}")
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { setBusy(false) }
            }
        }
    }

    private fun renderResolution(resolution: IntentReadModel.Resolution) {
        val lines = mutableListOf<String>()
        lines += "Status: ${resolution.status} (confidence ${"%.2f".format(resolution.confidence)})"
        resolution.action?.let { lines += "Action: $it${resolution.payload?.let { p -> " ($p)" } ?: ""}" }
        resolution.device?.let { device ->
            lines += "Device: ${device.displayName.ifBlank { device.id }} in ${device.room.ifBlank { "unassigned room" }}"
        }
        if (resolution.candidates.isNotEmpty()) {
            lines += "Candidates: " + resolution.candidates.joinToString(", ") { it.displayName.ifBlank { it.id } }
        }
        resolution.message?.takeIf { it.isNotBlank() }?.let { lines += it }

        resultContainer.children.setAll(
            lines.map { line ->
                Label(line).apply {
                    styleClass += "shell-section-subtitle"
                    isWrapText = true
                }
            }
        )
    }

    private fun beginExecute() {
        val resolution = lastResolution?.takeIf { it.isExecutable } ?: return
        val device = resolution.device ?: return
        val action = resolution.action ?: return
        executeAction(device.id, action, resolution.payload, confirm = false, deviceLabel = device.displayName.ifBlank { device.id })
    }

    private fun executeAction(deviceId: String, action: String, payload: String?, confirm: Boolean, deviceLabel: String) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        setBusy(true)
        statusPill.text = "Executing"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")

        worker.execute {
            try {
                val outcome = actionModel.execute(deviceId, action, payload, confirm)
                Platform.runLater {
                    inFlight.set(false)
                    setBusy(false)
                    when {
                        outcome.success -> {
                            statusPill.text = "Executed"
                            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                            resultContainer.children.add(
                                0,
                                Label("Executed ${outcome.action} on $deviceLabel.").apply { styleClass += "shell-section-subtitle" }
                            )
                        }
                        outcome.needsConfirmation && !confirm -> {
                            statusPill.text = "Needs confirmation"
                            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
                            if (confirmSecurityCriticalAction(deviceLabel, action)) {
                                executeAction(deviceId, action, payload, confirm = true, deviceLabel = deviceLabel)
                            }
                        }
                        else -> {
                            statusPill.text = "Failed"
                            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                            resultContainer.children.add(
                                0,
                                Label(outcome.message ?: "Action failed.").apply { styleClass += "shell-section-subtitle" }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    setBusy(false)
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    resultContainer.children.add(
                        0,
                        Label(e.message ?: "Action failed.").apply { styleClass += "shell-section-subtitle" }
                    )
                }
            }
        }
    }

    /** Security-critical devices (locks/doors/garages) require an explicit owner confirmation. */
    private fun confirmSecurityCriticalAction(deviceLabel: String, action: String): Boolean {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Confirm action"
        dialog.headerText = "Confirm $action on $deviceLabel"
        dialog.dialogPane.content = Label(
            "This is a security-critical device (lock/door/garage). Confirm to proceed."
        ).apply { isWrapText = true }
        val confirmButton = ButtonType("Confirm", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.setAll(confirmButton, ButtonType.CANCEL)
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == confirmButton
    }

    private fun setBusy(busy: Boolean) {
        resolveButton.isDisable = busy
        executeButton.isDisable = busy || lastResolution?.isExecutable != true
    }

    private fun renderPlaceholder(message: String) {
        resultContainer.children.setAll(
            Label(message).apply {
                styleClass += "shell-placeholder-body"
                isWrapText = true
            }
        )
    }
}
