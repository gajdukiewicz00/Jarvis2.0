package org.jarvis.desktop.features.planner

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.shell.ShellRouteContent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PlannerView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = PlannerReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-planner").apply { isDaemon = true }
    }
    private val refreshInFlight = AtomicBoolean(false)
    private val actionInFlight = AtomicBoolean(false)
    private val timeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

    private val updatedLabel = Label("Waiting for planner snapshot").apply {
        styleClass += "diagnostics-updated-label"
    }
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { refreshSnapshot() }
    }

    private val feedbackPill = statusPill("Planner")
    private val feedbackLabel = Label(
        "Planner shell uses the existing gateway-backed todo tooling that already works through the authenticated desktop client."
    ).apply {
        styleClass += "shell-section-subtitle"
        isWrapText = true
    }

    private val totalCountLabel = valueLabel()
    private val openCountLabel = valueLabel()
    private val doneCountLabel = valueLabel()

    private val titleField = TextField().apply {
        promptText = "Add a task title"
    }
    private val descriptionField = TextField().apply {
        promptText = "Optional description"
    }
    private val priorityCombo = ComboBox<String>().apply {
        items.addAll("LOW", "MEDIUM", "HIGH", "URGENT")
        value = "MEDIUM"
    }
    private val addButton = Button("Create task").apply {
        styleClass += "shell-action-button"
        setOnAction { createTask() }
    }

    private val tasksContainer = VBox(12.0).apply {
        styleClass += "planner-task-list"
    }

    init {
        styleClass += "shell-route-scroll"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
    }

    override fun onRouteActivated() {
        refreshSnapshot()
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(6.0).apply {
                children += Label("Planner").apply { styleClass += "shell-page-title" }
                children += Label(
                    "Tasks you can actually read and change today through existing planner tooling exposed by the current backend."
                ).apply {
                    styleClass += "shell-page-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += updatedLabel
            children += refreshButton
        }

        val feedbackRow = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += feedbackPill
            children += feedbackLabel
        }

        val summary = FlowPane(16.0, 16.0).apply {
            children.addAll(
                metricCard("Total tasks", totalCountLabel),
                metricCard("Open", openCountLabel),
                metricCard("Done", doneCountLabel)
            )
        }

        val quickCapture = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += Label("Quick capture").apply { styleClass += "shell-section-title" }
            children += Label(
                "Create a planner task through `/api/v1/tools/todo/create` without leaving the shell."
            ).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += FlowPane(12.0, 12.0).apply {
                children.addAll(titleField, descriptionField, priorityCombo, addButton)
            }
        }

        val tasksSection = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += Label("Tasks").apply { styleClass += "shell-section-title" }
            children += Label(
                "Read flow: `/api/v1/tools/todo/list`. Action flow: create and complete planner tasks."
            ).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += tasksContainer
        }

        return VBox(18.0).apply {
            styleClass += "shell-planner-view"
            padding = Insets(24.0)
            children.addAll(header, feedbackRow, summary, quickCapture, tasksSection)
        }
    }

    private fun refreshSnapshot() {
        if (!refreshInFlight.compareAndSet(false, true)) {
            return
        }

        refreshButton.isDisable = true
        feedbackPill.text = "Loading"
        applyTone(feedbackPill, "shell-status-tone-info")
        feedbackLabel.text = "Loading planner tasks from the existing tool proxy..."
        tasksContainer.children.setAll(stateCard("Loading planner tasks..."))

        worker.execute {
            try {
                val snapshot = readModel.loadSnapshot()
                Platform.runLater {
                    renderSnapshot(snapshot)
                    feedbackPill.text = "Ready"
                    applyTone(feedbackPill, "shell-status-tone-success")
                    feedbackLabel.text = "Planner tasks loaded from the gateway-backed todo tool flow."
                    updatedLabel.text = "Updated ${timeFormatter.format(java.time.Instant.now())}"
                }
            } catch (e: Exception) {
                Platform.runLater {
                    totalCountLabel.text = "0"
                    openCountLabel.text = "0"
                    doneCountLabel.text = "0"
                    feedbackPill.text = "Error"
                    applyTone(feedbackPill, "shell-status-tone-error")
                    feedbackLabel.text = e.message ?: "Planner load failed."
                    updatedLabel.text = "Planner load failed"
                    tasksContainer.children.setAll(
                        stateCard("Unable to load planner tasks.\n${e.message ?: "Unknown error"}")
                    )
                }
            } finally {
                refreshInFlight.set(false)
                Platform.runLater {
                    refreshButton.isDisable = false
                }
            }
        }
    }

    private fun createTask() {
        val title = titleField.text?.trim().orEmpty()
        if (title.isBlank()) {
            feedbackPill.text = "Input needed"
            applyTone(feedbackPill, "shell-status-tone-warning")
            feedbackLabel.text = "Task title is required before Planner can create a todo."
            return
        }
        if (!actionInFlight.compareAndSet(false, true)) {
            return
        }

        addButton.isDisable = true
        feedbackPill.text = "Saving"
        applyTone(feedbackPill, "shell-status-tone-info")
        feedbackLabel.text = "Creating planner task..."

        worker.execute {
            try {
                readModel.createTodo(title, descriptionField.text, priorityCombo.value ?: "MEDIUM")
                val snapshot = readModel.loadSnapshot()
                Platform.runLater {
                    titleField.clear()
                    descriptionField.clear()
                    priorityCombo.value = "MEDIUM"
                    renderSnapshot(snapshot)
                    feedbackPill.text = "Saved"
                    applyTone(feedbackPill, "shell-status-tone-success")
                    feedbackLabel.text = "Planner task created."
                    updatedLabel.text = "Updated ${timeFormatter.format(java.time.Instant.now())}"
                }
            } catch (e: Exception) {
                Platform.runLater {
                    feedbackPill.text = "Error"
                    applyTone(feedbackPill, "shell-status-tone-error")
                    feedbackLabel.text = e.message ?: "Planner task creation failed."
                }
            } finally {
                actionInFlight.set(false)
                Platform.runLater {
                    addButton.isDisable = false
                }
            }
        }
    }

    private fun completeTask(taskId: Long) {
        if (!actionInFlight.compareAndSet(false, true)) {
            return
        }

        feedbackPill.text = "Updating"
        applyTone(feedbackPill, "shell-status-tone-info")
        feedbackLabel.text = "Completing planner task..."

        worker.execute {
            try {
                readModel.completeTodo(taskId)
                val snapshot = readModel.loadSnapshot()
                Platform.runLater {
                    renderSnapshot(snapshot)
                    feedbackPill.text = "Updated"
                    applyTone(feedbackPill, "shell-status-tone-success")
                    feedbackLabel.text = "Planner task completed."
                    updatedLabel.text = "Updated ${timeFormatter.format(java.time.Instant.now())}"
                }
            } catch (e: Exception) {
                Platform.runLater {
                    feedbackPill.text = "Error"
                    applyTone(feedbackPill, "shell-status-tone-error")
                    feedbackLabel.text = e.message ?: "Planner task completion failed."
                }
            } finally {
                actionInFlight.set(false)
            }
        }
    }

    private fun renderSnapshot(snapshot: PlannerReadModel.Snapshot) {
        totalCountLabel.text = snapshot.totalCount.toString()
        openCountLabel.text = snapshot.openCount.toString()
        doneCountLabel.text = snapshot.doneCount.toString()

        tasksContainer.children.clear()
        if (snapshot.tasks.isEmpty()) {
            tasksContainer.children += stateCard("No planner tasks yet. Capture one above to populate this screen.")
            return
        }

        snapshot.tasks.forEach { task ->
            tasksContainer.children += taskCard(task)
        }
    }

    private fun taskCard(task: PlannerReadModel.TodoTask): VBox {
        val dueText = task.dueDate?.let(timeFormatter::format) ?: "No due date"
        return VBox(10.0).apply {
            styleClass.addAll("shell-section-card", "planner-task-card")
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += VBox(4.0).apply {
                    children += Label(task.title).apply { styleClass += "shell-section-title" }
                    children += Label("Priority ${task.priority}  |  Status ${task.status}  |  Due $dueText").apply {
                        styleClass += "shell-section-subtitle"
                        isWrapText = true
                    }
                }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                children += statusPill(task.status).apply {
                    applyTone(this, when (task.status) {
                        "DONE" -> "shell-status-tone-success"
                        "CANCELLED" -> "shell-status-tone-error"
                        else -> "shell-status-tone-info"
                    })
                }
            }
            task.description?.takeIf { it.isNotBlank() }?.let { description ->
                children += Label(description).apply {
                    styleClass += "shell-section-subtitle"
                    isWrapText = true
                }
            }
            if (task.tags.isNotEmpty()) {
                children += Label("Tags: ${task.tags.joinToString()}").apply {
                    styleClass += "shell-section-subtitle"
                    isWrapText = true
                }
            }
            if (task.status != "DONE" && task.status != "CANCELLED") {
                children += FlowPane(12.0, 12.0).apply {
                    children += Button("Complete task").apply {
                        styleClass += "shell-action-button"
                        setOnAction { completeTask(task.id) }
                    }
                }
            }
        }
    }

    private fun metricCard(title: String, value: Label): VBox {
        return VBox(8.0).apply {
            styleClass.addAll("shell-section-card", "planner-metric-card")
            prefWidth = 220.0
            children += Label(title).apply { styleClass += "shell-section-title" }
            children += value
        }
    }

    private fun stateCard(message: String): VBox {
        return VBox(8.0).apply {
            styleClass.addAll("shell-section-card", "shell-placeholder")
            children += Label(message).apply {
                styleClass += "shell-placeholder-body"
                isWrapText = true
            }
        }
    }

    private fun valueLabel(): Label {
        return Label("0").apply {
            styleClass += "diagnostics-metric-value"
        }
    }

    private fun statusPill(text: String): Label {
        return Label(text).apply {
            styleClass.addAll("shell-status-pill", "shell-status-tone-muted")
        }
    }

    private fun applyTone(label: Label, toneClass: String) {
        val toneClasses = setOf(
            "shell-status-tone-muted",
            "shell-status-tone-info",
            "shell-status-tone-success",
            "shell-status-tone-warning",
            "shell-status-tone-error"
        )
        label.styleClass.removeIf { it in toneClasses }
        if (toneClass !in label.styleClass) {
            label.styleClass += toneClass
        }
    }
}
