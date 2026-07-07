package org.jarvis.desktop.features.planner

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Dialog
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
import java.time.Instant
import java.time.LocalDate
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
    private val dueDatePicker = DatePicker().apply {
        promptText = "Due date (optional)"
    }
    private val addButton = Button("Create task").apply {
        styleClass += "shell-action-button"
        setOnAction { createTask() }
    }

    private val tasksContainer = VBox(12.0).apply {
        styleClass += "planner-task-list"
    }

    private val planModeCombo = ComboBox<PlannerReadModel.PlanModeOption>().apply {
        items.addAll(PlannerReadModel.PLAN_MODE_OPTIONS)
        value = PlannerReadModel.PLAN_MODE_OPTIONS.first()
    }
    private val applyPlanModeButton = Button("Apply plan mode").apply {
        styleClass += "shell-action-button"
        setOnAction { applyPlanMode() }
    }
    private val planByModeLabel = Label("Loading plan-mode ranking…").apply {
        styleClass += "shell-section-subtitle"
        isWrapText = true
    }

    private val focusLabel = Label("Loading today's focus…").apply {
        styleClass += "shell-section-subtitle"
        isWrapText = true
    }
    private val eveningReviewLabel = Label("Loading evening review…").apply {
        styleClass += "shell-section-subtitle"
        isWrapText = true
    }
    private val weeklyPlanLabel = Label("Loading this week's plan…").apply {
        styleClass += "shell-section-subtitle"
        isWrapText = true
    }
    private val tomorrowPlanLabel = Label("Loading tomorrow's plan…").apply {
        styleClass += "shell-section-subtitle"
        isWrapText = true
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
                children.addAll(titleField, descriptionField, priorityCombo, dueDatePicker, addButton)
            }
        }

        val planModeSection = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += Label("Plan mode").apply { styleClass += "shell-section-title" }
            children += Label(
                "Set the day's plan mode via `POST /api/v1/planner/plan/mode`, ranked by `/plan/by-mode`."
            ).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += FlowPane(12.0, 12.0).apply {
                children.addAll(planModeCombo, applyPlanModeButton)
            }
            children += planByModeLabel
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

        val briefSection = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += Label("Daily focus & evening review").apply { styleClass += "shell-section-title" }
            children += Label(
                "Live from `/api/v1/planner/focus` and `/api/v1/planner/evening-review`."
            ).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += Label("Today's focus").apply { styleClass += "shell-section-title" }
            children += focusLabel
            children += Label("Evening review").apply { styleClass += "shell-section-title" }
            children += eveningReviewLabel
        }

        val outlookSection = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += Label("This week & tomorrow").apply { styleClass += "shell-section-title" }
            children += Label(
                "Live from `/api/v1/planner/weekly` and `/api/v1/planner/daily?date=<tomorrow>`."
            ).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += Label("This week").apply { styleClass += "shell-section-title" }
            children += weeklyPlanLabel
            children += Label("Tomorrow").apply { styleClass += "shell-section-title" }
            children += tomorrowPlanLabel
        }

        return VBox(18.0).apply {
            styleClass += "shell-planner-view"
            padding = Insets(24.0)
            children.addAll(
                header, feedbackRow, summary, briefSection, outlookSection,
                planModeSection, quickCapture, tasksSection
            )
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
                val focus = readModel.loadFocus()
                val eveningReview = readModel.loadEveningReview()
                val weeklyPlan = readModel.loadWeeklyPlan()
                val tomorrowPlan = readModel.loadTomorrowPlan()
                val currentPlanMode = readModel.loadPlanMode()
                val planByMode = readModel.loadPlanByMode()
                Platform.runLater {
                    renderSnapshot(snapshot)
                    renderBrief(focusLabel, focus)
                    renderBrief(eveningReviewLabel, eveningReview)
                    renderBrief(weeklyPlanLabel, weeklyPlan)
                    renderBrief(tomorrowPlanLabel, tomorrowPlan)
                    planModeCombo.value = PlannerReadModel.PLAN_MODE_OPTIONS.firstOrNull { it.code == currentPlanMode }
                        ?: planModeCombo.value
                    renderBrief(planByModeLabel, planByMode)
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

        val dueDate = dueDatePicker.value?.toDueInstant()

        worker.execute {
            try {
                readModel.createTodo(title, descriptionField.text, priorityCombo.value ?: "MEDIUM", dueDate)
                val snapshot = readModel.loadSnapshot()
                Platform.runLater {
                    titleField.clear()
                    descriptionField.clear()
                    priorityCombo.value = "MEDIUM"
                    dueDatePicker.value = null
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

    private fun applyPlanMode() {
        val option = planModeCombo.value ?: return
        if (!actionInFlight.compareAndSet(false, true)) {
            return
        }

        applyPlanModeButton.isDisable = true
        feedbackPill.text = "Saving"
        applyTone(feedbackPill, "shell-status-tone-info")
        feedbackLabel.text = "Applying plan mode..."

        worker.execute {
            try {
                readModel.setPlanMode(option.code)
                val planByMode = readModel.loadPlanByMode()
                Platform.runLater {
                    renderBrief(planByModeLabel, planByMode)
                    feedbackPill.text = "Saved"
                    applyTone(feedbackPill, "shell-status-tone-success")
                    feedbackLabel.text = "Plan mode set to ${option.label}."
                    updatedLabel.text = "Updated ${timeFormatter.format(java.time.Instant.now())}"
                }
            } catch (e: Exception) {
                Platform.runLater {
                    feedbackPill.text = "Error"
                    applyTone(feedbackPill, "shell-status-tone-error")
                    feedbackLabel.text = e.message ?: "Applying plan mode failed."
                }
            } finally {
                actionInFlight.set(false)
                Platform.runLater {
                    applyPlanModeButton.isDisable = false
                }
            }
        }
    }

    private fun editTask(task: PlannerReadModel.TodoTask) {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Edit task"
        dialog.headerText = "Editing \"${task.title}\""

        val editTitleField = TextField(task.title)
        val editDescriptionField = TextField(task.description.orEmpty())
        val editPriorityCombo = ComboBox<String>().apply {
            items.addAll("LOW", "MEDIUM", "HIGH", "URGENT")
            value = task.priority
        }
        val editDueDatePicker = DatePicker(task.dueDate?.atZone(ZoneId.systemDefault())?.toLocalDate())

        dialog.dialogPane.content = VBox(10.0).apply {
            padding = Insets(12.0)
            children += Label("Title")
            children += editTitleField
            children += Label("Description")
            children += editDescriptionField
            children += Label("Priority")
            children += editPriorityCombo
            children += Label("Due date")
            children += editDueDatePicker
        }
        val saveButtonType = ButtonType("Save", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.setAll(saveButtonType, ButtonType.CANCEL)

        dialog.showAndWait().ifPresent { result ->
            if (result != saveButtonType) return@ifPresent
            val newTitle = editTitleField.text?.trim().orEmpty()
            if (newTitle.isBlank()) {
                feedbackPill.text = "Input needed"
                applyTone(feedbackPill, "shell-status-tone-warning")
                feedbackLabel.text = "Task title is required before Planner can save an edit."
                return@ifPresent
            }
            submitEdit(
                task.id,
                newTitle,
                editDescriptionField.text,
                editPriorityCombo.value ?: task.priority,
                editDueDatePicker.value?.toDueInstant()
            )
        }
    }

    private fun submitEdit(id: Long, title: String, description: String?, priority: String, dueDate: Instant?) {
        if (!actionInFlight.compareAndSet(false, true)) {
            return
        }

        feedbackPill.text = "Saving"
        applyTone(feedbackPill, "shell-status-tone-info")
        feedbackLabel.text = "Updating planner task..."

        worker.execute {
            try {
                readModel.updateTodo(id, title, description, priority, dueDate)
                val snapshot = readModel.loadSnapshot()
                Platform.runLater {
                    renderSnapshot(snapshot)
                    feedbackPill.text = "Saved"
                    applyTone(feedbackPill, "shell-status-tone-success")
                    feedbackLabel.text = "Planner task updated."
                    updatedLabel.text = "Updated ${timeFormatter.format(java.time.Instant.now())}"
                }
            } catch (e: Exception) {
                Platform.runLater {
                    feedbackPill.text = "Error"
                    applyTone(feedbackPill, "shell-status-tone-error")
                    feedbackLabel.text = e.message ?: "Planner task update failed."
                }
            } finally {
                actionInFlight.set(false)
            }
        }
    }

    private fun deleteTask(task: PlannerReadModel.TodoTask) {
        if (!confirmDeletion(task.title)) {
            return
        }
        if (!actionInFlight.compareAndSet(false, true)) {
            return
        }

        feedbackPill.text = "Deleting"
        applyTone(feedbackPill, "shell-status-tone-info")
        feedbackLabel.text = "Deleting planner task..."

        worker.execute {
            try {
                readModel.deleteTodo(task.id)
                val snapshot = readModel.loadSnapshot()
                Platform.runLater {
                    renderSnapshot(snapshot)
                    feedbackPill.text = "Deleted"
                    applyTone(feedbackPill, "shell-status-tone-success")
                    feedbackLabel.text = "Planner task deleted."
                    updatedLabel.text = "Updated ${timeFormatter.format(java.time.Instant.now())}"
                }
            } catch (e: Exception) {
                Platform.runLater {
                    feedbackPill.text = "Error"
                    applyTone(feedbackPill, "shell-status-tone-error")
                    feedbackLabel.text = e.message ?: "Planner task deletion failed."
                }
            } finally {
                actionInFlight.set(false)
            }
        }
    }

    private fun confirmDeletion(taskTitle: String): Boolean {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Delete task"
        dialog.headerText = "Delete \"$taskTitle\"?"
        dialog.dialogPane.content = Label(
            "This permanently removes the task. This cannot be undone."
        ).apply { isWrapText = true }
        val deleteButton = ButtonType("Delete", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.setAll(deleteButton, ButtonType.CANCEL)
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == deleteButton
    }

    private fun skipOccurrence(taskId: Long) {
        runOccurrenceAction("Skipping occurrence...", "Occurrence skipped.", "Skipping occurrence failed.") {
            readModel.skipOccurrence(taskId)
        }
    }

    private fun completeOccurrence(taskId: Long) {
        runOccurrenceAction(
            "Completing occurrence...",
            "Occurrence completed.",
            "Completing occurrence failed."
        ) {
            readModel.completeOccurrence(taskId)
        }
    }

    private fun generateNextOccurrences(taskId: Long) {
        runOccurrenceAction(
            "Generating next occurrences...",
            "Generated the next occurrences.",
            "Generating next occurrences failed."
        ) {
            readModel.generateNextOccurrences(taskId)
        }
    }

    private fun runOccurrenceAction(
        inProgressMessage: String,
        successMessage: String,
        failureMessage: String,
        action: () -> Unit
    ) {
        if (!actionInFlight.compareAndSet(false, true)) {
            return
        }

        feedbackPill.text = "Updating"
        applyTone(feedbackPill, "shell-status-tone-info")
        feedbackLabel.text = inProgressMessage

        worker.execute {
            try {
                action()
                val snapshot = readModel.loadSnapshot()
                Platform.runLater {
                    renderSnapshot(snapshot)
                    feedbackPill.text = "Updated"
                    applyTone(feedbackPill, "shell-status-tone-success")
                    feedbackLabel.text = successMessage
                    updatedLabel.text = "Updated ${timeFormatter.format(java.time.Instant.now())}"
                }
            } catch (e: Exception) {
                Platform.runLater {
                    feedbackPill.text = "Error"
                    applyTone(feedbackPill, "shell-status-tone-error")
                    feedbackLabel.text = e.message ?: failureMessage
                }
            } finally {
                actionInFlight.set(false)
            }
        }
    }

    private fun LocalDate.toDueInstant(): Instant = atStartOfDay(ZoneId.systemDefault()).toInstant()

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

    private fun renderBrief(label: Label, result: PlannerReadModel.BriefResult) {
        when (result) {
            is PlannerReadModel.BriefResult.Available -> label.text = result.text
            is PlannerReadModel.BriefResult.Unavailable ->
                label.text = "Временно недоступно: ${result.reason}"
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
                if (task.isRecurringOccurrence || task.isRecurringTemplate) {
                    children += statusPill("↻ Recurring").apply {
                        applyTone(this, "shell-status-tone-info")
                    }
                }
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
            children += FlowPane(12.0, 12.0).apply {
                children += Button("Edit").apply {
                    styleClass += "shell-action-button"
                    setOnAction { editTask(task) }
                }
                children += Button("Delete").apply {
                    styleClass += "shell-action-button-danger"
                    setOnAction { deleteTask(task) }
                }
                if (task.status != "DONE" && task.status != "CANCELLED") {
                    when {
                        task.isRecurringOccurrence -> {
                            children += Button("Skip occurrence").apply {
                                styleClass += "shell-action-button"
                                setOnAction { skipOccurrence(task.id) }
                            }
                            children += Button("Complete occurrence").apply {
                                styleClass += "shell-action-button"
                                setOnAction { completeOccurrence(task.id) }
                            }
                        }
                        else -> {
                            children += Button("Complete task").apply {
                                styleClass += "shell-action-button"
                                setOnAction { completeTask(task.id) }
                            }
                        }
                    }
                }
                if (task.isRecurringTemplate) {
                    children += Button("Generate next occurrences").apply {
                        styleClass += "shell-action-button"
                        setOnAction { generateNextOccurrences(task.id) }
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
