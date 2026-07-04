package org.jarvis.desktop.features.smarthome

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.features.common.ShellPanelSupport
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Scenes" section embedded in the Smart Home route — a named set of device
 * actions applied together (e.g. "movie-night" -> dim lights, lock door).
 *
 * Wires:
 *  - list scenes    -> GET    /api/v1/smarthome/scenes
 *  - create scene   -> POST   /api/v1/smarthome/scenes
 *  - delete scene   -> DELETE /api/v1/smarthome/scenes/{name}
 *  - activate scene -> POST   /api/v1/smarthome/scenes/{name}/activate
 *
 * Embedded as a plain [Node] (not a [org.jarvis.desktop.shell.ShellRouteContent])
 * — the containing [SmartHomeView] owns the shell route lifecycle and forwards
 * [refresh] / [shutdown] calls into this section.
 */
class ScenesView(
    apiClient: ApiClient
) : VBox(12.0) {
    companion object {
        private val COMMON_ACTIONS = listOf(
            "TOGGLE", "TURN_ON", "TURN_OFF", "DIM", "BRIGHTEN",
            "SET_COLOR", "SET_TEMPERATURE", "LOCK", "UNLOCK"
        )
    }

    private val readModel = ScenesReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-scenes").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Scenes")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "Named sets of device actions applied together. Live from `/api/v1/smarthome/scenes`."
    )
    private val newSceneButton = Button("New scene").apply {
        styleClass += "shell-action-button"
        setOnAction { openCreateDialog() }
    }
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { refresh() }
    }

    private val scenesContainer = VBox(12.0)

    init {
        styleClass += "shell-section-card"
        children += HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += ShellPanelSupport.sectionTitle("Scenes")
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += statusPill
            children += newSceneButton
            children += refreshButton
        }
        children += statusLabel
        children += scenesContainer
        renderPlaceholder("Loading scenes…")
    }

    fun refresh() {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        setControlsDisabled(true)
        statusPill.text = "Loading"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "Loading scenes…"

        worker.execute {
            try {
                val scenes = readModel.loadScenes()
                Platform.runLater {
                    renderScenes(scenes)
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    statusLabel.text = "${scenes.size} scene(s)."
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Scenes request failed."
                    renderPlaceholder("Unable to load scenes.\n${e.message ?: "Unknown error"}")
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { setControlsDisabled(false) }
            }
        }
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    private fun setControlsDisabled(disabled: Boolean) {
        newSceneButton.isDisable = disabled
        refreshButton.isDisable = disabled
    }

    private fun renderScenes(scenes: List<ScenesReadModel.Scene>) {
        if (scenes.isEmpty()) {
            renderPlaceholder("No scenes yet. Use \"New scene\" above to create one.")
            return
        }
        scenesContainer.children.setAll(scenes.map(::sceneCard))
    }

    private fun sceneCard(scene: ScenesReadModel.Scene): Node {
        return VBox(8.0).apply {
            styleClass.addAll("shell-section-card", "scene-card")
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label(scene.name).apply { styleClass += "shell-section-title" }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += Button("Activate").apply {
                    styleClass += "shell-action-button"
                    setOnAction { activateScene(scene.name) }
                }
                children += Button("Delete").apply {
                    styleClass += "shell-action-button-danger"
                    setOnAction { confirmDelete(scene.name) }
                }
            }
            children += Label(stepsSummary(scene.steps)).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
        }
    }

    private fun stepsSummary(steps: List<ScenesReadModel.SceneStep>): String {
        if (steps.isEmpty()) return "No steps configured."
        return steps.joinToString("; ") { step ->
            val payload = step.payload?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            "${step.deviceId} → ${step.action}$payload"
        }
    }

    private fun activateScene(name: String) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusPill.text = "Activating"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "Activating \"$name\"…"

        worker.execute {
            try {
                val result = readModel.activateScene(name)
                Platform.runLater {
                    statusPill.text = "Activated"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    statusLabel.text = "\"$name\" activated (${result.applied} step(s)): ${result.summary}"
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to activate \"$name\"."
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun confirmDelete(name: String) {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Delete scene"
        dialog.headerText = "Delete \"$name\"?"
        dialog.dialogPane.content = Label(
            "This removes the scene definition. It can be re-created later with the same name."
        ).apply { isWrapText = true }
        val deleteButton = ButtonType("Delete", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.setAll(deleteButton, ButtonType.CANCEL)

        val confirmed = dialog.showAndWait().orElse(ButtonType.CANCEL) == deleteButton
        if (confirmed) {
            deleteScene(name)
        }
    }

    private fun deleteScene(name: String) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusPill.text = "Deleting"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
        statusLabel.text = "Deleting \"$name\"…"

        worker.execute {
            try {
                readModel.deleteScene(name)
                val scenes = readModel.loadScenes()
                Platform.runLater {
                    renderScenes(scenes)
                    statusPill.text = "Deleted"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    statusLabel.text = "\"$name\" deleted."
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to delete \"$name\"."
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun openCreateDialog() {
        val dialog = Dialog<ButtonType>()
        dialog.title = "New scene"
        dialog.headerText = "Create a scene from one or more device steps"

        val nameField = TextField().apply { promptText = "Scene name (e.g. movie-night)" }
        val deviceField = TextField().apply { promptText = "Device id" }
        val actionCombo = ComboBox<String>().apply {
            items.addAll(COMMON_ACTIONS)
            value = COMMON_ACTIONS.first()
        }
        val payloadField = TextField().apply { promptText = "Optional payload" }
        val pendingSteps = mutableListOf<ScenesReadModel.SceneStep>()
        val stepsView = ListView<String>().apply { prefHeight = 120.0 }
        val addStepButton = Button("Add step").apply {
            setOnAction {
                val deviceId = deviceField.text?.trim().orEmpty()
                if (deviceId.isBlank()) {
                    return@setOnAction
                }
                val step = ScenesReadModel.SceneStep(
                    deviceId = deviceId,
                    action = actionCombo.value ?: COMMON_ACTIONS.first(),
                    payload = payloadField.text
                )
                pendingSteps += step
                stepsView.items += formatStep(step)
                deviceField.clear()
                payloadField.clear()
            }
        }
        val removeStepButton = Button("Remove selected").apply {
            setOnAction {
                val index = stepsView.selectionModel.selectedIndex
                if (index in pendingSteps.indices) {
                    pendingSteps.removeAt(index)
                    stepsView.items.removeAt(index)
                }
            }
        }

        dialog.dialogPane.content = VBox(10.0).apply {
            padding = Insets(12.0)
            children += Label("Name")
            children += nameField
            children += Label("Steps (optional)")
            children += FlowPane(8.0, 8.0).apply {
                children.addAll(deviceField, actionCombo, payloadField, addStepButton)
            }
            children += stepsView
            children += removeStepButton
        }
        val createButton = ButtonType("Create", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.setAll(createButton, ButtonType.CANCEL)

        dialog.showAndWait().ifPresent { result ->
            if (result == createButton) {
                submitCreate(nameField.text, pendingSteps.toList())
            }
        }
    }

    private fun formatStep(step: ScenesReadModel.SceneStep): String {
        val payload = step.payload?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return "${step.deviceId} → ${step.action}$payload"
    }

    private fun submitCreate(name: String?, steps: List<ScenesReadModel.SceneStep>) {
        val trimmedName = name?.trim().orEmpty()
        if (trimmedName.isBlank()) {
            statusPill.text = "Input needed"
            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
            statusLabel.text = "Scene name is required."
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusPill.text = "Saving"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "Creating scene \"$trimmedName\"…"

        worker.execute {
            try {
                readModel.createScene(trimmedName, steps)
                val scenes = readModel.loadScenes()
                Platform.runLater {
                    renderScenes(scenes)
                    statusPill.text = "Saved"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    statusLabel.text = "\"$trimmedName\" created."
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Error"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to create scene."
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun renderPlaceholder(message: String) {
        scenesContainer.children.setAll(
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
