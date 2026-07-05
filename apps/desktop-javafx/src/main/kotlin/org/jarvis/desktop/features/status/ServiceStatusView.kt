package org.jarvis.desktop.features.status

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.agent.status.StatusAggregator
import org.jarvis.desktop.shell.ShellRouteContent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Model/service status panel: per-service health (LLM, memory, voice
 * gateway, vision security...) plus a "show the command" surface for
 * repair/update/rollback so operators never have to guess the exact script.
 *
 * Repair/update/rollback intentionally do NOT execute anything from this
 * process — update and rollback mutate the live k3s deployment
 * (`jarvis-restore-deploy.sh` / `jarvis-deploy-prod.sh` run `kubectl`
 * directly), so the desktop shell only prepares the exact command on the
 * clipboard for the operator to run themselves from a terminal.
 */
class ServiceStatusView(
    private val readModel: ServiceStatusReadModel = ServiceStatusReadModel()
) : ScrollPane(), ShellRouteContent {

    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-service-status").apply { isDaemon = true }
    }
    private val refreshInFlight = AtomicBoolean(false)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private val summaryPill = statusPill("Checking services")
    private val summaryLabel = Label().apply {
        styleClass += "shell-section-subtitle"
        isWrapText = true
    }
    private val updatedLabel = Label("Waiting for status snapshot")
    private val refreshButton = Button("Refresh")
    private val servicesContainer = VBox(10.0).apply { styleClass += "diagnostics-check-list" }
    private val opsFeedbackLabel = Label(
        "Repair, update, and rollback act on the live cluster — pick one to copy the exact " +
            "command, then run it yourself from a terminal."
    ).apply {
        styleClass += "shell-section-subtitle"
        isWrapText = true
    }

    private var refreshTask: ScheduledFuture<*>? = null

    init {
        styleClass += "shell-route-scroll"
        isFitToWidth = true
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        content = buildContent()

        refreshButton.setOnAction { refreshNow() }
    }

    override fun onRouteActivated() {
        refreshNow()
        startAutoRefresh()
    }

    override fun onRouteDeactivated() {
        stopAutoRefresh()
    }

    override fun onShellShutdown() {
        stopAutoRefresh()
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(4.0).apply {
                children += Label("Service Status").apply { styleClass += "shell-page-title" }
                children += Label(
                    "Model and service health across the running Jarvis stack."
                ).apply {
                    styleClass += "shell-page-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += updatedLabel.apply { styleClass += "diagnostics-updated-label" }
            children += refreshButton.apply { styleClass += "shell-action-button" }
        }

        val summaryRow = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += summaryPill
            children += summaryLabel
        }

        val servicesSection = sectionCard(
            title = "Services",
            subtitle = "Polls health endpoints for the LLM/model, memory, voice, and vision services " +
                "through the resolved API gateway.",
            body = servicesContainer
        )

        val opsSection = sectionCard(
            title = "Repair / Update / Rollback",
            subtitle = "These operate on the live cluster. The desktop shell only prepares the exact " +
                "command — copy and run it yourself.",
            body = VBox(10.0).apply {
                children += opsFeedbackLabel
                children += FlowPane(12.0, 12.0).apply {
                    children.addAll(
                        opsButton("Repair endpoint", REPAIR_COMMAND),
                        opsButton("Update (deploy release)", UPDATE_COMMAND),
                        opsButton("Rollback feature images", ROLLBACK_COMMAND)
                    )
                }
            }
        )

        return VBox(18.0).apply {
            styleClass += "shell-service-status-view"
            padding = Insets(24.0)
            children.addAll(header, summaryRow, servicesSection, opsSection)
        }
    }

    private fun opsButton(label: String, command: String): Button {
        return Button(label).apply {
            styleClass += "shell-action-button"
            setOnAction { copyCommand(label, command) }
        }
    }

    private fun copyCommand(label: String, command: String) {
        val clipboard = Clipboard.getSystemClipboard()
        clipboard.setContent(ClipboardContent().apply { putString(command) })
        opsFeedbackLabel.text = "Copied to clipboard — $label:  $command"
    }

    private fun sectionCard(title: String, subtitle: String, body: Node): VBox {
        return VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += Label(title).apply { styleClass += "shell-section-title" }
            children += Label(subtitle).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += body
        }
    }

    private fun refreshNow() {
        if (!refreshInFlight.compareAndSet(false, true)) {
            return
        }

        refreshButton.isDisable = true
        updatedLabel.text = "Refreshing service status..."

        worker.execute {
            try {
                val snapshot = readModel.refresh()
                Platform.runLater { render(snapshot) }
            } catch (e: Exception) {
                Platform.runLater {
                    updatedLabel.text = "Service status refresh failed"
                    summaryPill.text = "Refresh failed"
                    applyTone(summaryPill, "shell-status-tone-error")
                    summaryLabel.text = e.message ?: "Unknown error while refreshing service status."
                }
            } finally {
                refreshInFlight.set(false)
                Platform.runLater { refreshButton.isDisable = false }
            }
        }
    }

    private fun startAutoRefresh() {
        stopAutoRefresh()
        refreshTask = worker.scheduleAtFixedRate({ refreshNow() }, 15, 15, TimeUnit.SECONDS)
    }

    private fun stopAutoRefresh() {
        refreshTask?.cancel(false)
        refreshTask = null
    }

    private fun render(snapshot: ServiceStatusReadModel.Snapshot) {
        updatedLabel.text = "Updated ${timeFormatter.format(snapshot.refreshedAt)}"

        val up = snapshot.services.count { it.status == StatusAggregator.ProbeStatus.UP }
        val degraded = snapshot.services.count { it.status == StatusAggregator.ProbeStatus.DEGRADED }
        val down = snapshot.services.count { it.status == StatusAggregator.ProbeStatus.DOWN }

        summaryPill.text = "$up/${snapshot.services.size} services up"
        applyTone(
            summaryPill,
            when {
                down > 0 -> "shell-status-tone-error"
                degraded > 0 -> "shell-status-tone-warning"
                snapshot.services.isNotEmpty() -> "shell-status-tone-success"
                else -> "shell-status-tone-muted"
            }
        )
        summaryLabel.text = "Target ${snapshot.baseUrl}  |  Up $up  |  Degraded $degraded  |  Down $down"

        servicesContainer.children.clear()
        if (snapshot.services.isEmpty()) {
            servicesContainer.children += Label("No services configured.").apply {
                styleClass += "diagnostics-empty-state"
            }
            return
        }

        snapshot.services.forEach { service ->
            servicesContainer.children += VBox(6.0).apply {
                styleClass += "diagnostics-check-row"
                children += HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children += Label(service.name).apply { styleClass += "diagnostics-check-title" }
                    val spacer = Region()
                    HBox.setHgrow(spacer, Priority.ALWAYS)
                    children += spacer
                    children += statusPill(service.status.name).apply { applyTone(this, toneFor(service.status)) }
                }
                service.detail?.let { detail ->
                    children += Label(detail).apply {
                        styleClass += "diagnostics-check-detail"
                        isWrapText = true
                    }
                }
            }
        }
    }

    private fun toneFor(status: StatusAggregator.ProbeStatus): String = when (status) {
        StatusAggregator.ProbeStatus.UP -> "shell-status-tone-success"
        StatusAggregator.ProbeStatus.DEGRADED -> "shell-status-tone-warning"
        StatusAggregator.ProbeStatus.DOWN -> "shell-status-tone-error"
    }

    private fun statusPill(text: String): Label {
        return Label(text).apply {
            styleClass.addAll("shell-status-pill", "diagnostics-status-pill", "shell-status-tone-muted")
        }
    }

    private fun applyTone(label: Label, toneClass: String) {
        label.styleClass.removeIf {
            it == "shell-status-pill" || it == "diagnostics-status-pill" || it.startsWith("shell-status-tone-")
        }
        label.styleClass.addAll("shell-status-pill", "diagnostics-status-pill", toneClass)
    }

    private companion object {
        const val REPAIR_COMMAND = "./scripts/jarvis-final-check.sh --repair"
        const val UPDATE_COMMAND = "./scripts/product/jarvis-deploy-prod.sh"
        const val ROLLBACK_COMMAND = "./scripts/jarvis-restore-deploy.sh"
    }
}
