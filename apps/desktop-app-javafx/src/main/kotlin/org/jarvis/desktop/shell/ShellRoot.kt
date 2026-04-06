package org.jarvis.desktop.shell

import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.features.diagnostics.DiagnosticsView
import org.jarvis.desktop.features.home.HomeView
import org.jarvis.desktop.features.settings.SettingsView
import org.jarvis.desktop.features.voice.VoiceView
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.jarvis.desktop.runtime.LocalRuntimeHealthProbe
import org.jarvis.desktop.service.AuthService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ShellRoot : BorderPane() {
    private val navigator = ShellNavigator()
    private val topBar = ShellTopBar(navigator)
    private val navPane = ShellNavPane(navigator)
    private val authService = AuthService()
    private val apiClient = ApiClient(authService = authService)
    private val runtimeMonitor = DesktopRuntimeMonitor()
    private val runtimeHealthProbe = LocalRuntimeHealthProbe(
        apiGatewayBaseUrlProvider = { AppConfig.current().apiGatewayBaseUrl }
    )
    private val routeViews = mutableMapOf<ShellRoute, Node>()
    private val runtimeExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-runtime").apply { isDaemon = true }
    }
    private val contentHost = StackPane()
    private val shutdownRequested = AtomicBoolean(false)

    private var runtimeTask: ScheduledFuture<*>? = null
    private var activeRoute: ShellRoute? = null
    private val routeListener: (ShellRoute) -> Unit = ::showRoute
    private val configListener: (ResolvedDesktopConfig) -> Unit = { config ->
        Platform.runLater { topBar.renderConfig(config) }
    }
    private val runtimeListener: (DesktopRuntimeMonitor.Snapshot) -> Unit = { snapshot ->
        Platform.runLater { topBar.renderRuntime(snapshot) }
    }

    init {
        styleClass += "shell-root"
        top = topBar
        left = navPane
        center = contentHost.apply {
            styleClass += "shell-content-host"
            padding = javafx.geometry.Insets(24.0)
        }

        runtimeMonitor.addListener(runtimeListener)
        AppConfig.addListener(configListener)
        navigator.addListener(routeListener)

        topBar.renderConfig(AppConfig.current())

        runtimeMonitor.updateVoice(
            DesktopRuntimeMonitor.ConnectionStatus(
                DesktopRuntimeMonitor.ConnectionState.UNKNOWN,
                "Voice route is hosted through a legacy adapter until the shell-native screen is rebuilt",
                java.time.Instant.now()
            )
        )
        runtimeMonitor.updatePcControl(
            DesktopRuntimeMonitor.ConnectionStatus(
                DesktopRuntimeMonitor.ConnectionState.UNKNOWN,
                "Diagnostics and desktop actions stay in existing clients for now",
                java.time.Instant.now()
            )
        )
        runtimeMonitor.recordEvent(
            DesktopRuntimeMonitor.EventSource.SYSTEM,
            DesktopRuntimeMonitor.EventSeverity.INFO,
            "Unified desktop shell ready",
            "Minimal shell bootstrap is running alongside legacy clients."
        )

        startRuntimeHealthPolling()
    }

    fun shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            return
        }

        stopRuntimeHealthPolling()
        runtimeExecutor.shutdownNow()
        activeRouteContent()?.onRouteDeactivated()
        routeViews.values.forEach { routeNode ->
            routeNode.routeContent()?.onShellShutdown()
        }
        navigator.removeListener(routeListener)
        topBar.dispose()
        navPane.dispose()
        runtimeMonitor.removeListener(runtimeListener)
        AppConfig.removeListener(configListener)
    }

    private fun routeView(route: ShellRoute): Node {
        return routeViews.getOrPut(route) {
            when (route) {
                ShellRoute.HOME -> HomeView(
                    runtimeMonitor = runtimeMonitor,
                    onRefreshRuntime = ::refreshRuntimeHealthNow,
                    onOpenVoice = { navigator.navigateTo(ShellRoute.VOICE) },
                    onOpenDiagnostics = { navigator.navigateTo(ShellRoute.DIAGNOSTICS) },
                    onOpenSettings = { navigator.navigateTo(ShellRoute.SETTINGS) }
                )
                ShellRoute.VOICE -> VoiceView(apiClient, runtimeMonitor)
                ShellRoute.DIAGNOSTICS -> DiagnosticsView(apiClient)
                ShellRoute.SETTINGS -> SettingsView(apiClient, ::handleLogout)
            }
        }
    }

    private fun showRoute(route: ShellRoute) {
        if (activeRoute == route) {
            return
        }

        activeRouteContent()?.onRouteDeactivated()

        val nextNode = routeView(route)
        contentHost.children.setAll(nextNode)
        nextNode.routeContent()?.onRouteActivated()
        activeRoute = route
    }

    private fun activeRouteNode(): Node? = activeRoute?.let(routeViews::get)

    private fun activeRouteContent(): ShellRouteContent? = activeRouteNode()?.routeContent()

    private fun Node.routeContent(): ShellRouteContent? = this as? ShellRouteContent

    private fun startRuntimeHealthPolling() {
        stopRuntimeHealthPolling()
        runtimeTask = runtimeExecutor.scheduleAtFixedRate(
            { refreshRuntimeHealthNow() },
            0,
            5,
            TimeUnit.SECONDS
        )
    }

    private fun stopRuntimeHealthPolling() {
        runtimeTask?.cancel(true)
        runtimeTask = null
    }

    private fun refreshRuntimeHealthNow() {
        runtimeMonitor.updateBackend(runtimeHealthProbe.probe())
    }

    private fun handleLogout() {
        TokenManager.clearTokens()
        runtimeMonitor.recordEvent(
            DesktopRuntimeMonitor.EventSource.SYSTEM,
            DesktopRuntimeMonitor.EventSeverity.INFO,
            "Desktop session cleared",
            "Tokens were removed from the unified shell session."
        )
        topBar.renderConfig(AppConfig.current())
    }
}
