package org.jarvis.desktop.shell

import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.KeyCombination
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.agent.command.DefaultDesktopActions
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.desktop.features.agentswarm.AgentSwarmView
import org.jarvis.desktop.features.analytics.AnalyticsView
import org.jarvis.desktop.features.ai.AiView
import org.jarvis.desktop.features.brain.BrainChatView
import org.jarvis.desktop.features.controlcenter.ControlCenterView
import org.jarvis.desktop.features.diagnostics.DiagnosticsView
import org.jarvis.desktop.features.finance.FinanceReviewView
import org.jarvis.desktop.features.finance.FinanceView
import org.jarvis.desktop.features.home.HomeView
import org.jarvis.desktop.features.insights.InsightsView
import org.jarvis.desktop.features.life.LifeMapView
import org.jarvis.desktop.features.media.MediaJobsView
import org.jarvis.desktop.features.memory.MemoryView
import org.jarvis.desktop.features.panic.PanicControlService
import org.jarvis.desktop.features.pccontrol.PcControlView
import org.jarvis.desktop.features.planner.PlannerView
import org.jarvis.desktop.features.proactive.ProactiveView
import org.jarvis.desktop.features.security.SecuritySessionsView
import org.jarvis.desktop.features.security.SecurityView
import org.jarvis.desktop.features.settings.SettingsView
import org.jarvis.desktop.features.smarthome.SmartHomeView
import org.jarvis.desktop.features.status.ServiceStatusReadModel
import org.jarvis.desktop.features.status.ServiceStatusView
import org.jarvis.desktop.features.sync.SyncPairingView
import org.jarvis.desktop.features.vision.VisionSecurityView
import org.jarvis.desktop.features.voice.VoiceHelpView
import org.jarvis.desktop.features.voice.VoiceView
import org.jarvis.desktop.onboarding.OnboardingMarker
import org.jarvis.desktop.onboarding.OnboardingWizardView
import org.jarvis.desktop.palette.CommandPaletteAction
import org.jarvis.desktop.palette.CommandPaletteOverlay
import org.jarvis.desktop.palette.PaletteEntry
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.jarvis.desktop.runtime.LocalRuntimeHealthProbe
import org.jarvis.desktop.service.AuthService
import org.jarvis.desktop.service.PcControlWebSocketClient
import org.jarvis.desktop.service.SystemControlService
import org.jarvis.desktop.service.TransportErrorFormatter
import org.jarvis.desktop.theme.ThemePreference
import org.jarvis.launcher.JarvisPaths
import org.jarvis.launcher.LauncherConfig
import org.jarvis.launcher.LauncherSettings
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.prefs.PreferenceChangeListener

class ShellRoot(
    private val onLogoutRequested: () -> Unit
) : BorderPane() {
    private val navigator = ShellNavigator(ShellRoute.CONTROL_CENTER)
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
    private val panicControlService = PanicControlService(apiClient)
    private val topBar = ShellTopBar(
        navigator,
        panicControlService = panicControlService,
        onOpenCommandPalette = ::toggleCommandPalette,
        onOpenServiceStatus = { navigator.navigateTo(ShellRoute.SERVICE_STATUS) }
    )
    private val serviceStatusReadModel = ServiceStatusReadModel()
    private val onboardingMarker = OnboardingMarker()
    private val agentLiveFeed = AgentLiveFeed()
    private val desktopActions = runCatching { DefaultDesktopActions() }.getOrNull()
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
    private var serviceStatusTask: ScheduledFuture<*>? = null
    private var activeRoute: ShellRoute? = null
    private var onboardingOverlay: Node? = null
    private var commandPaletteOverlay: Node? = null
    private var themeListenerHandle: PreferenceChangeListener? = null
    private val commandPaletteEntries: List<CommandPaletteAction> by lazy { buildCommandPaletteEntries() }

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
        sceneProperty().addListener { _, _, newScene ->
            if (newScene != null) {
                registerAccelerators(newScene)
                applyTheme(ThemePreference.isEnabled())
            }
        }
        themeListenerHandle = ThemePreference.addListener { enabled -> Platform.runLater { applyTheme(enabled) } }

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
        startServiceStatusPolling()
        initPcControlWebSocket()
        maybeShowOnboarding()
    }

    fun shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            return
        }

        stopRuntimeHealthPolling()
        serviceStatusTask?.cancel(true)
        themeListenerHandle?.let { ThemePreference.removeListener(it) }
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
                ShellRoute.CONTROL_CENTER -> ControlCenterView(
                    onNavigate = { route -> navigator.navigateTo(route) }
                )
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
                ShellRoute.BRAIN -> BrainChatView(apiClient)
                ShellRoute.VOICE_HELP -> VoiceHelpView(
                    apiClient = apiClient,
                    onOpenVoiceControl = { navigator.navigateTo(ShellRoute.VOICE) }
                )
                ShellRoute.MEMORY -> MemoryView(apiClient)
                ShellRoute.FINANCE -> FinanceView(apiClient)
                ShellRoute.PLANNER -> PlannerView(apiClient)
                ShellRoute.LIFE -> LifeMapView(
                    apiClient = apiClient,
                    liveFeed = agentLiveFeed,
                    desktopActions = desktopActions
                )
                ShellRoute.ANALYTICS -> AnalyticsView(apiClient)
                ShellRoute.INSIGHTS -> InsightsView(apiClient)
                ShellRoute.PC_CONTROL -> PcControlView(apiClient)
                ShellRoute.SMART_HOME -> SmartHomeView(apiClient)
                ShellRoute.VISION_SECURITY -> VisionSecurityView(apiClient)
                ShellRoute.PROACTIVE -> ProactiveView(apiClient)
                ShellRoute.SECURITY -> SecurityView(apiClient)
                ShellRoute.SECURITY_SESSIONS -> SecuritySessionsView(apiClient)
                ShellRoute.AGENT_SWARM -> AgentSwarmView(apiClient)
                ShellRoute.MEDIA_JOBS -> MediaJobsView(apiClient)
                ShellRoute.FINANCE_REVIEW -> FinanceReviewView(apiClient)
                ShellRoute.SYNC -> SyncPairingView(apiClient)
                ShellRoute.VOICE -> VoiceView(apiClient, runtimeMonitor)
                ShellRoute.DIAGNOSTICS -> DiagnosticsView(apiClient)
                ShellRoute.SERVICE_STATUS -> ServiceStatusView()
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

    private fun startServiceStatusPolling() {
        serviceStatusTask = runtimeExecutor.scheduleAtFixedRate(
            { refreshServiceStatusNow() },
            0,
            20,
            TimeUnit.SECONDS
        )
    }

    private fun refreshServiceStatusNow() {
        try {
            val snapshot = serviceStatusReadModel.refresh()
            Platform.runLater { topBar.renderServiceStatus(snapshot) }
        } catch (_: Exception) {
            // Top bar simply keeps its last-known state; the dedicated Service Status
            // route surfaces the failure message in detail on its own refresh cycle.
        }
    }

    private fun registerAccelerators(scene: Scene) {
        scene.accelerators[KeyCombination.keyCombination("Shortcut+K")] = Runnable { toggleCommandPalette() }
    }

    private fun applyTheme(enabled: Boolean) {
        val activeScene = scene ?: return
        val stylesheetUrl = requireNotNull(ShellRoot::class.java.getResource("/css/stark-lab.css")) {
            "Stark Lab stylesheet not found"
        }.toExternalForm()

        if (enabled) {
            if (stylesheetUrl !in activeScene.stylesheets) {
                activeScene.stylesheets += stylesheetUrl
            }
        } else {
            activeScene.stylesheets -= stylesheetUrl
        }
    }

    private fun maybeShowOnboarding() {
        if (onboardingMarker.isComplete()) {
            return
        }
        val overlay = OnboardingWizardView(onFinish = ::dismissOnboarding, onSkip = ::dismissOnboarding)
        onboardingOverlay = overlay
        contentHost.children += overlay
    }

    private fun dismissOnboarding() {
        onboardingMarker.markComplete()
        onboardingOverlay?.let { contentHost.children.remove(it) }
        onboardingOverlay = null
    }

    private fun toggleCommandPalette() {
        if (commandPaletteOverlay != null) {
            closeCommandPalette()
            return
        }

        val overlay = CommandPaletteOverlay(
            entries = commandPaletteEntries,
            onClose = ::closeCommandPalette
        )
        commandPaletteOverlay = overlay
        contentHost.children += overlay
        overlay.focusSearchField()
    }

    private fun closeCommandPalette() {
        commandPaletteOverlay?.let { contentHost.children.remove(it) }
        commandPaletteOverlay = null
    }

    private fun buildCommandPaletteEntries(): List<CommandPaletteAction> {
        val navigationEntries = visibleRoutes.map { route ->
            CommandPaletteAction(
                entry = PaletteEntry(
                    id = "route-${route.name}",
                    label = route.navLabel,
                    category = "Navigate",
                    keywords = listOf(route.title)
                ),
                action = { navigator.navigateTo(route) }
            )
        }

        val actionEntries = listOf(
            CommandPaletteAction(
                entry = PaletteEntry("action-panic-engage", "Engage panic kill-switch", "Safety"),
                action = { runPanicAction(engage = true) }
            ),
            CommandPaletteAction(
                entry = PaletteEntry("action-panic-clear", "Clear panic kill-switch", "Safety"),
                action = { runPanicAction(engage = false) }
            ),
            CommandPaletteAction(
                entry = PaletteEntry("action-theme-toggle", "Toggle Stark Lab theme", "Appearance"),
                action = { ThemePreference.setEnabled(!ThemePreference.isEnabled()) }
            )
        )

        return navigationEntries + actionEntries
    }

    private fun runPanicAction(engage: Boolean) {
        runtimeExecutor.execute {
            try {
                val snapshot = if (engage) panicControlService.engage() else panicControlService.clear()
                runtimeMonitor.recordEvent(
                    DesktopRuntimeMonitor.EventSource.SYSTEM,
                    if (snapshot.engaged) DesktopRuntimeMonitor.EventSeverity.ERROR else DesktopRuntimeMonitor.EventSeverity.INFO,
                    if (snapshot.engaged) "Panic engaged from command palette" else "Panic cleared from command palette",
                    snapshot.detail
                )
            } catch (e: Exception) {
                runtimeMonitor.recordEvent(
                    DesktopRuntimeMonitor.EventSource.SYSTEM,
                    DesktopRuntimeMonitor.EventSeverity.ERROR,
                    "Panic action failed",
                    e.message ?: "Unknown error"
                )
            }
        }
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
