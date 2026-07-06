package org.jarvis.desktop.features.agentswarm

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.features.common.ShellPanelSupport
import org.jarvis.desktop.shell.ShellRouteContent
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent Swarm panel — the "House Party Protocol" multi-role coding swarm
 * surfaced from agent-service: role catalog, task states, a dry-run swarm
 * trigger, and the combined report for a run.
 *
 * The trigger only ever sends `dryRun=true` from this GUI — a real
 * (side-effecting) run is deliberately not exposed here.
 */
class AgentSwarmView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = AgentSwarmReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-agent-swarm").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Agent Swarm")
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { refresh() }
    }

    private val rolesContainer = VBox(8.0)
    private val tasksContainer = VBox(10.0)

    private val roleChecks: List<CheckBox> = KNOWN_ROLES.map { role -> CheckBox(role) }
    private val goalField = TextArea().apply {
        promptText = "Swarm goal (e.g. \"Add input validation to the login form\")"
        isWrapText = true
        prefRowCount = 2
    }
    private val startButton = Button("Start dry-run swarm").apply {
        styleClass += "shell-action-button"
        setOnAction { startSwarm() }
    }
    private val startResult = ShellPanelSupport.sectionSubtitle("")

    private val swarmIdField = TextField().apply { promptText = "swarmId" }
    private val fetchReportButton = Button("Fetch report").apply {
        styleClass += "shell-action-button"
        setOnAction { fetchReport() }
    }
    private val reportArea = TextArea().apply {
        isEditable = false
        isWrapText = true
        prefRowCount = 8
        styleClass += "diagnostics-readonly-area"
        text = "Start a dry-run swarm, or paste a swarm id above, then fetch its report."
    }

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-agent-swarm-view"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
        renderRolesPlaceholder("Refresh to load the role catalog.")
        renderTasksPlaceholder("Refresh to load your agent tasks.")
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
                children += Label("Agent Swarm").apply { styleClass += "shell-page-title" }
                children += Label("Roles, tasks, and dry-run swarm runs (CODER/TESTER/RESEARCH/DOCS/SECURITY/MEDIA/FINANCE).").apply {
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

        val rolesCard = VBox(10.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Role catalog")
            children += rolesContainer
        }

        val triggerCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Start a dry-run swarm")
            children += ShellPanelSupport.sectionSubtitle(
                "Fans one goal out across the selected roles. This panel always runs with dryRun=true, so roles propose without side effects."
            )
            children += goalField
            children += FlowPane(10.0, 6.0).apply { children.addAll(roleChecks) }
            children += HBox(12.0, startButton).apply { alignment = Pos.CENTER_LEFT }
            children += startResult
        }

        val reportCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Combined report")
            children += HBox(10.0).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(swarmIdField, Priority.ALWAYS)
                swarmIdField.maxWidth = Double.MAX_VALUE
                children.addAll(swarmIdField, fetchReportButton)
            }
            children += reportArea
        }

        val tasksCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Tasks")
            children += tasksContainer
        }

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, rolesCard, triggerCard, reportCard, tasksCard)
        }
    }

    private fun refresh() {
        runBusy("Loading roles and tasks…") {
            val roles = readModel.listRoles()
            val tasks = readModel.listTasks()
            Platform.runLater {
                renderRoles(roles)
                renderTasks(tasks)
            }
        }
    }

    private fun startSwarm() {
        val goal = goalField.text?.trim().orEmpty()
        val selectedRoles = roleChecks.filter { it.isSelected }.map { it.text }
        if (goal.isBlank()) {
            startResult.text = "Enter a goal first."
            return
        }
        if (selectedRoles.isEmpty()) {
            startResult.text = "Select at least one role."
            return
        }
        runBusy("Starting dry-run swarm…") {
            val started = readModel.startDryRunSwarm(goal, selectedRoles)
            Platform.runLater {
                swarmIdField.text = started.swarmId
                startResult.text = "Started swarmId=${started.swarmId} · roles=${started.roles.joinToString(", ")} · dryRun=${started.dryRun}"
            }
        }
    }

    private fun fetchReport() {
        val swarmId = swarmIdField.text?.trim().orEmpty()
        if (swarmId.isBlank()) {
            reportArea.text = "Enter a swarm id first (filled in automatically after starting a run)."
            return
        }
        runBusy("Fetching combined report…") {
            val report = readModel.report(swarmId)
            Platform.runLater { reportArea.text = formatReport(report) }
        }
    }

    private fun runBusy(progress: String, block: () -> Unit) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        setBusy(true)
        statusPill.text = "Working"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")

        worker.execute {
            try {
                block()
                Platform.runLater {
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    startResult.text = e.message ?: "Agent swarm request failed."
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { setBusy(false) }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        refreshButton.isDisable = busy
        startButton.isDisable = busy
        fetchReportButton.isDisable = busy
    }

    private fun renderRoles(roles: List<AgentSwarmReadModel.RoleInfo>) {
        if (roles.isEmpty()) {
            renderRolesPlaceholder("No roles returned by the agent service.")
            return
        }
        rolesContainer.children.setAll(
            roles.map { role ->
                HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    val pill = ShellPanelSupport.statusPill(role.role)
                    ShellPanelSupport.applyTone(pill, if (role.sandboxRequired) "shell-status-tone-warning" else "shell-status-tone-info")
                    children += pill
                    children += Label(role.description).apply {
                        isWrapText = true
                        styleClass += "shell-section-subtitle"
                    }
                }
            }
        )
    }

    private fun renderTasks(tasks: List<AgentSwarmReadModel.TaskInfo>) {
        if (tasks.isEmpty()) {
            renderTasksPlaceholder("No agent tasks yet.")
            return
        }
        tasksContainer.children.setAll(tasks.map(::taskCard))
    }

    private fun taskCard(task: AgentSwarmReadModel.TaskInfo): Node {
        return VBox(6.0).apply {
            styleClass += "shell-section-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label("${task.role} · ${task.taskId}").apply { styleClass += "shell-section-title" }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                val pill = ShellPanelSupport.statusPill(task.status)
                ShellPanelSupport.applyTone(
                    pill,
                    if (task.isAwaitingApproval) "shell-status-tone-warning" else statusTone(task.status)
                )
                children += pill
                children += Button("Cancel").apply {
                    styleClass += "shell-action-button"
                    isDisable = task.isTerminal
                    setOnAction { cancelTask(task.taskId) }
                }
            }
            children += Label(task.goal).apply {
                isWrapText = true
                styleClass += "shell-section-subtitle"
            }
            (task.resultSummary ?: task.errorMessage)?.takeIf { it.isNotBlank() }?.let {
                children += Label(it).apply {
                    isWrapText = true
                    styleClass += "shell-section-subtitle"
                }
            }
            if (task.isAwaitingApproval) {
                children += HBox(8.0).apply {
                    alignment = Pos.CENTER_RIGHT
                    children += Button("Reject").apply {
                        styleClass += "shell-action-button-danger"
                        setOnAction { rejectTask(task.taskId) }
                    }
                    children += Button("Approve").apply {
                        styleClass += "shell-action-button"
                        setOnAction { approveTask(task.taskId) }
                    }
                }
            }
            children += HBox(8.0).apply {
                alignment = Pos.CENTER_RIGHT
                children += Button("Download diff").apply {
                    styleClass += "shell-action-button"
                    setOnAction { downloadArtifact(task.taskId, "diff.patch") { readModel.downloadDiff(task.taskId) } }
                }
                children += Button("Download report").apply {
                    styleClass += "shell-action-button"
                    setOnAction { downloadArtifact(task.taskId, "${task.taskId}-report.md") { readModel.downloadReport(task.taskId) } }
                }
            }
        }
    }

    private fun cancelTask(taskId: String) {
        runBusy("Cancelling task…") {
            readModel.cancelTask(taskId)
            val tasks = readModel.listTasks()
            Platform.runLater { renderTasks(tasks) }
        }
    }

    private fun approveTask(taskId: String) {
        runBusy("Approving task…") {
            readModel.approveTask(taskId)
            val tasks = readModel.listTasks()
            Platform.runLater {
                renderTasks(tasks)
                startResult.text = "Approved task $taskId — patch applied to its sandbox."
            }
        }
    }

    private fun rejectTask(taskId: String) {
        runBusy("Rejecting task…") {
            readModel.rejectTask(taskId)
            val tasks = readModel.listTasks()
            Platform.runLater {
                renderTasks(tasks)
                startResult.text = "Rejected task $taskId — nothing was applied."
            }
        }
    }

    /** Fetches an artifact's text content and lets the owner save it to a local file. */
    private fun downloadArtifact(taskId: String, suggestedFileName: String, fetch: () -> String) {
        runBusy("Fetching artifact…") {
            val content = fetch()
            Platform.runLater {
                val chooser = FileChooser().apply {
                    title = "Save artifact for task $taskId"
                    initialFileName = suggestedFileName
                }
                val target: File? = chooser.showSaveDialog(scene?.window)
                if (target != null) {
                    target.writeText(content)
                    startResult.text = "Saved ${target.name}."
                } else {
                    startResult.text = "Artifact fetched — save cancelled."
                }
            }
        }
    }

    private fun statusTone(status: String): String = when (status) {
        "COMPLETED" -> "shell-status-tone-success"
        "FAILED", "CANCELLED" -> "shell-status-tone-error"
        "RUNNING", "QUEUED" -> "shell-status-tone-info"
        else -> "shell-status-tone-muted"
    }

    private fun formatReport(report: AgentSwarmReadModel.CombinedReportInfo): String {
        val sb = StringBuilder()
        sb.append("swarmId: ${report.swarmId}\n")
        sb.append("goal: ${report.goal}\n")
        sb.append("complete: ${report.complete}\n")
        sb.append("rolesUsed: ${report.rolesUsed.joinToString(", ")}\n")
        if (report.perRole.isNotEmpty()) {
            sb.append("\nPer-role outcomes:\n")
            report.perRole.forEach { outcome ->
                sb.append("  • ${outcome.role} [${outcome.status}] ${outcome.summary}\n")
            }
        }
        if (report.failedRoles.isNotEmpty()) {
            sb.append("\nFailed roles: ${report.failedRoles.joinToString(", ")}\n")
        }
        if (report.risks.isNotEmpty()) {
            sb.append("\nRisks:\n")
            report.risks.forEach { sb.append("  • $it\n") }
        }
        if (report.nextActions.isNotEmpty()) {
            sb.append("\nNext actions:\n")
            report.nextActions.forEach { sb.append("  • $it\n") }
        }
        return sb.toString().trim()
    }

    private fun renderRolesPlaceholder(message: String) {
        rolesContainer.children.setAll(Label(message).apply {
            styleClass += "shell-placeholder-body"
            isWrapText = true
        })
    }

    private fun renderTasksPlaceholder(message: String) {
        tasksContainer.children.setAll(
            VBox(6.0).apply {
                styleClass.addAll("shell-section-card", "shell-placeholder")
                children += Label(message).apply {
                    styleClass += "shell-placeholder-body"
                    isWrapText = true
                }
            }
        )
    }

    private companion object {
        val KNOWN_ROLES = listOf("CODER", "TESTER", "RESEARCH", "DOCS", "SECURITY", "MEDIA", "FINANCE")
    }
}
