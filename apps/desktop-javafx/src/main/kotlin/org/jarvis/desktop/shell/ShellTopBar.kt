package org.jarvis.desktop.shell

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.agent.status.StatusAggregator
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.features.panic.PanicControlService
import org.jarvis.desktop.features.status.ServiceStatusReadModel
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ShellTopBar(
    private val navigator: ShellNavigator,
    private val panicControlService: PanicControlService = PanicControlService(ApiClient()),
    private val onOpenCommandPalette: () -> Unit = {},
    private val onOpenServiceStatus: () -> Unit = {}
) : HBox(18.0) {
    private val routeTitle = Label()
    private val backendStatusPill = Label("Checking backend")
    private val servicesStatusPill = Label("Services: checking").apply {
        styleClass.addAll("shell-status-pill", "shell-services-pill", "shell-status-tone-muted")
    }
    private val endpointLabel = Label()
    private val notificationsLabel = Label("Alerts: 0")
    private val profileLabel = Label()
    private val panicButton = ToggleButton("Panic").apply {
        styleClass.addAll("shell-panic-button", "shell-action-button-danger")
        tooltip = Tooltip(
            "Engage the global panic kill-switch — halts every automated action path"
        )
    }

    private val panicWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-topbar-panic").apply { isDaemon = true }
    }
    private val panicInFlight = AtomicBoolean(false)

    private val routeListener: (ShellRoute) -> Unit = { route ->
        routeTitle.text = route.title
    }

    init {
        styleClass += "shell-top-bar"
        alignment = Pos.CENTER_LEFT
        padding = Insets(18.0, 24.0, 18.0, 24.0)

        val titleBlock = VBox(2.0).apply {
            alignment = Pos.CENTER_LEFT
            children += Label("Jarvis").apply {
                styleClass += "shell-app-title"
            }
            children += routeTitle.apply {
                styleClass += "shell-route-title"
            }
        }

        val commandField = TextField().apply {
            promptText = "Search or type a command  (Ctrl+K)"
            isFocusTraversable = false
            styleClass += "shell-command-field"
            maxWidth = 420.0
            setOnMouseClicked { onOpenCommandPalette() }
        }

        val spacer = Region()
        HBox.setHgrow(spacer, Priority.ALWAYS)

        val statusRow = HBox(10.0).apply {
            alignment = Pos.CENTER_RIGHT
            children += backendStatusPill.apply { styleClass += "shell-status-pill" }
            children += servicesStatusPill.apply { setOnMouseClicked { onOpenServiceStatus() } }
            children += panicButton
            children += endpointLabel.apply { styleClass += "shell-top-meta" }
            children += notificationsLabel.apply { styleClass += "shell-top-meta" }
            children += profileLabel.apply { styleClass += "shell-profile-chip" }
        }

        children.addAll(titleBlock, commandField, spacer, statusRow)
        navigator.addListener(routeListener)

        panicButton.setOnAction { togglePanic() }
    }

    fun dispose() {
        navigator.removeListener(routeListener)
        panicWorker.shutdownNow()
    }

    fun renderConfig(config: ResolvedDesktopConfig) {
        endpointLabel.text = config.apiGatewayBaseUrl
        profileLabel.text = TokenManager.getUsername() ?: "Offline user"
    }

    fun renderRuntime(snapshot: DesktopRuntimeMonitor.Snapshot) {
        backendStatusPill.text = when (snapshot.backend.state) {
            DesktopRuntimeMonitor.ConnectionState.CONNECTED -> "Backend healthy"
            DesktopRuntimeMonitor.ConnectionState.CONNECTING -> "Backend connecting"
            DesktopRuntimeMonitor.ConnectionState.DEGRADED -> "Backend degraded"
            DesktopRuntimeMonitor.ConnectionState.DISCONNECTED -> "Backend offline"
            DesktopRuntimeMonitor.ConnectionState.ERROR -> "Backend error"
            DesktopRuntimeMonitor.ConnectionState.UNKNOWN -> "Backend unknown"
        }

        backendStatusPill.styleClass.removeIf { it.startsWith("shell-status-tone-") }
        val modifier = when (snapshot.backend.state) {
            DesktopRuntimeMonitor.ConnectionState.CONNECTED -> "shell-status-tone-success"
            DesktopRuntimeMonitor.ConnectionState.CONNECTING -> "shell-status-tone-info"
            DesktopRuntimeMonitor.ConnectionState.DEGRADED -> "shell-status-tone-warning"
            DesktopRuntimeMonitor.ConnectionState.DISCONNECTED,
            DesktopRuntimeMonitor.ConnectionState.ERROR -> "shell-status-tone-error"
            DesktopRuntimeMonitor.ConnectionState.UNKNOWN -> "shell-status-tone-muted"
        }
        backendStatusPill.styleClass += modifier
        notificationsLabel.text = "Alerts: ${snapshot.events.count { it.severity == DesktopRuntimeMonitor.EventSeverity.ERROR }}"
    }

    /** Pushed periodically by the shell owner — this Node never polls network itself. */
    fun renderServiceStatus(snapshot: ServiceStatusReadModel.Snapshot) {
        val healthy = snapshot.healthyCount
        val total = snapshot.services.size
        servicesStatusPill.text = "Services: $healthy/$total up"
        servicesStatusPill.styleClass.removeIf { it.startsWith("shell-status-tone-") }
        val downServices = snapshot.downServices
        val down = downServices.count { it.status == StatusAggregator.ProbeStatus.DOWN }
        val degraded = downServices.count { it.status == StatusAggregator.ProbeStatus.DEGRADED }
        servicesStatusPill.styleClass += when {
            down > 0 -> "shell-status-tone-error"
            degraded > 0 -> "shell-status-tone-warning"
            total > 0 -> "shell-status-tone-success"
            else -> "shell-status-tone-muted"
        }
        servicesStatusPill.tooltip = if (downServices.isEmpty()) {
            Tooltip("All $total service(s) reachable. Click to open Service Status.")
        } else {
            Tooltip(
                "Down/degraded:\n" +
                    downServices.joinToString("\n") { svc ->
                        "- ${svc.name} (${svc.status.name})" + (svc.detail?.let { ": $it" } ?: "")
                    } +
                    "\nClick to open Service Status."
            )
        }
    }

    private fun togglePanic() {
        if (!panicInFlight.compareAndSet(false, true)) {
            // Revert the click — a request is already in flight.
            panicButton.isSelected = !panicButton.isSelected
            return
        }

        val engaging = panicButton.isSelected
        panicButton.isDisable = true

        panicWorker.execute {
            try {
                val snapshot = if (engaging) panicControlService.engage() else panicControlService.clear()
                Platform.runLater { renderPanic(snapshot) }
            } catch (e: Exception) {
                Platform.runLater {
                    panicButton.isSelected = !engaging
                    notificationsLabel.text = "Panic action failed: ${e.message ?: "unknown error"}"
                }
            } finally {
                panicInFlight.set(false)
                Platform.runLater { panicButton.isDisable = false }
            }
        }
    }

    private fun renderPanic(snapshot: PanicControlService.PanicSnapshot) {
        panicButton.isSelected = snapshot.engaged
        panicButton.text = if (snapshot.engaged) "Panic engaged" else "Panic"
        panicButton.tooltip = Tooltip(snapshot.detail)
    }
}
