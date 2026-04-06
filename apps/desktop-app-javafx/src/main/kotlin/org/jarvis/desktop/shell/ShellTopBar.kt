package org.jarvis.desktop.shell

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor

class ShellTopBar(
    private val navigator: ShellNavigator
) : HBox(18.0) {
    private val routeTitle = Label()
    private val backendStatusPill = Label("Checking backend")
    private val endpointLabel = Label()
    private val notificationsLabel = Label("Alerts: 0")
    private val profileLabel = Label()
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
            promptText = "Search or type a command"
            isFocusTraversable = false
            styleClass += "shell-command-field"
            maxWidth = 420.0
        }

        val spacer = Region()
        HBox.setHgrow(spacer, Priority.ALWAYS)

        val statusRow = HBox(10.0).apply {
            alignment = Pos.CENTER_RIGHT
            children += backendStatusPill.apply { styleClass += "shell-status-pill" }
            children += endpointLabel.apply { styleClass += "shell-top-meta" }
            children += notificationsLabel.apply { styleClass += "shell-top-meta" }
            children += profileLabel.apply { styleClass += "shell-profile-chip" }
        }

        children.addAll(titleBlock, commandField, spacer, statusRow)
        navigator.addListener(routeListener)
    }

    fun dispose() {
        navigator.removeListener(routeListener)
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
}
