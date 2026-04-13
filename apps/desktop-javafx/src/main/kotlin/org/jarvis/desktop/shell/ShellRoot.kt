package org.jarvis.desktop.shell

import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.features.analytics.AnalyticsView
import org.jarvis.desktop.features.ai.AiView
import org.jarvis.desktop.features.diagnostics.DiagnosticsView
import org.jarvis.desktop.features.home.HomeView
import org.jarvis.desktop.features.life.LifeView
import org.jarvis.desktop.features.pccontrol.PcControlView
import org.jarvis.desktop.features.planner.PlannerView
import org.jarvis.desktop.features.settings.SettingsView
import org.jarvis.desktop.features.smarthome.SmartHomeView
import org.jarvis.desktop.features.vision.VisionSecurityView
import org.jarvis.desktop.features.voice.VoiceView
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.jarvis.desktop.runtime.LocalRuntimeHealthProbe
import org.jarvis.desktop.service.AuthService
import org.jarvis.desktop.service.PcControlWebSocketClient
import org.jarvis.desktop.service.SystemControlService
import org.jarvis.desktop.service.TransportErrorFormatter
import org.jarvis.launcher.JarvisPaths
import org.jarvis.launcher.LauncherConfig
import org.jarvis.launcher.LauncherSettings
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ShellRoot(
    private val onLogoutRequested: () -> Unit
) : BorderPane() {
    private val navigator = ShellNavigator()
    private val topBar = ShellTopBar(navigator)
    private val launcherSettings: LauncherSettings = runCatching {
        LauncherConfig(JarvisPaths.launcherConfig).load()
    }.getOrDefault(LauncherSettings())
    private val visibleRoutes: List<ShellRoute> = ShellRoute.visibleRoutes(
        runtimeMode = JarvisPaths.getRuntimeMode(),
        llmEnabled = launcherSettings.enableLlm,
        memoryEnabled = launcherSettings.enableMemory
    )
    private val navPane = ShellNavPane(navigator, visibleRoutes)
    private val authService = AuthService()
    private val apiClient = ApiClient(authService = authService)
    private val runtimeMonitor = DesktopRuntimeMonitor()
    private val systemControlService = SystemControlService()
    private var pcWebSocketClient: PcControlWebSocketClient? = null
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
                "Desktop action channel is initializing",
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
        initPcControlWebSocket()
    }

    fun shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            return
        }

        stopRuntimeHealthPolling()
        runtimeExecutor.shutdownNow()
        pcWebSocketClient?.disconnect()
        pcWebSocketClient = null
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

    private fun initPcControlWebSocket() {
        pcWebSocketClient?.disconnect()
        pcWebSocketClient = PcControlWebSocketClient(
            systemControl = systemControlService,
            onStatusChange = { status ->
                runtimeMonitor.consumePcStatus(status)
            }
        )

        Thread {
            try {
                Thread.sleep(2_000)
                pcWebSocketClient?.connect()
            } catch (e: Exception) {
                val formatted = TransportErrorFormatter.describeFailure(
                    channel = "PC Control WebSocket",
                    endpoint = AppConfig.current().pcControlWebSocketUrl,
                    throwable = e
                )
                runtimeMonitor.consumePcStatus("Connection failed: ${formatted.userMessage}")
                runtimeMonitor.recordEvent(
                    DesktopRuntimeMonitor.EventSource.PC_CONTROL,
                    DesktopRuntimeMonitor.EventSeverity.ERROR,
                    "Desktop actions failed",
                    formatted.userMessage
                )
            }
        }.apply {
            isDaemon = true
            name = "jarvis-desktop-shell-pc-control"
            start()
        }
    }

    private fun routeView(route: ShellRoute): Node {
        return routeViews.getOrPut(route) {
            when (route) {
                ShellRoute.HOME -> HomeView(
                    runtimeMonitor = runtimeMonitor,
                    onRefreshRuntime = ::refreshRuntimeHealthNow,
                    onOpenPlanner = { navigator.navigateTo(ShellRoute.PLANNER) },
                    onOpenLife = { navigator.navigateTo(ShellRoute.LIFE) },
                    onOpenAnalytics = { navigator.navigateTo(ShellRoute.ANALYTICS) },
                    onOpenPcControl = { navigator.navigateTo(ShellRoute.PC_CONTROL) },
                    onOpenSmartHome = { navigator.navigateTo(ShellRoute.SMART_HOME) },
                    onOpenVision = if (ShellRoute.VISION_SECURITY in visibleRoutes) {
                        { navigator.navigateTo(ShellRoute.VISION_SECURITY) }
                    } else {
                        null
                    },
                    onOpenVoice = { navigator.navigateTo(ShellRoute.VOICE) },
                    onOpenDiagnostics = { navigator.navigateTo(ShellRoute.DIAGNOSTICS) },
                    onOpenSettings = { navigator.navigateTo(ShellRoute.SETTINGS) }
                )
                ShellRoute.PLANNER -> PlannerView(apiClient)
                ShellRoute.LIFE -> LifeView(apiClient)
                ShellRoute.ANALYTICS -> AnalyticsView(apiClient)
                ShellRoute.PC_CONTROL -> PcControlView(apiClient)
                ShellRoute.SMART_HOME -> SmartHomeView(apiClient)
                ShellRoute.VISION_SECURITY -> VisionSecurityView(apiClient)
                ShellRoute.VOICE -> VoiceView(apiClient, runtimeMonitor)
                ShellRoute.DIAGNOSTICS -> DiagnosticsView(apiClient)
                ShellRoute.SETTINGS -> SettingsView(apiClient, ::handleLogout)
                ShellRoute.AI -> AiView()
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
        authService.logout()
        TokenManager.clearTokens()
        runtimeMonitor.recordEvent(
            DesktopRuntimeMonitor.EventSource.SYSTEM,
            DesktopRuntimeMonitor.EventSeverity.INFO,
            "Desktop session cleared",
            "Tokens were removed from the unified shell session."
        )
        onLogoutRequested()
    }
}
