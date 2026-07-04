package org.jarvis.desktop.features.life

import javafx.scene.layout.BorderPane
import org.jarvis.agent.command.DesktopActions
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.features.planner.PlannerReadModel
import org.jarvis.desktop.lifemap.LifeMapClient
import org.jarvis.desktop.lifemap.LifeMapPanel
import org.jarvis.desktop.shell.ShellRouteContent

/**
 * Shell route content for the unified Life Map.
 *
 * <p>This view is the canonical Life route in the desktop shell. It owns a
 * {@link LifeMapPanel} and binds it to the real providers backing each
 * tab: life-tracker (finance), planner-service (tasks), the desktop-agent
 * active-window probe (activity), and memory-service (insights). Health is
 * an explicit DEGRADED placeholder until Phase 12 wires Google Fit / Health
 * Connect.</p>
 *
 * <p>The legacy {@link LifeView} is kept on disk for ad-hoc dev parity but
 * is no longer routed.</p>
 */
class LifeMapView(
    apiClient: ApiClient,
    liveFeed: AgentLiveFeed,
    lifeMapClient: LifeMapClient = LifeMapClient(AppConfig.current().apiBaseUrl.removeSuffix("/api/v1")),
    plannerReadModel: PlannerReadModel = PlannerReadModel(apiClient),
    desktopActions: DesktopActions? = null,
    userIdProvider: () -> String = ::resolveUserId
) : BorderPane(), ShellRouteContent {

    private val providers = LifeMapProviders(plannerReadModel, desktopActions, apiClient)

    private val panel = LifeMapPanel(
        client = lifeMapClient,
        userId = userIdProvider(),
        liveFeed = liveFeed,
        tasksProvider = providers::loadTasks,
        activityProvider = providers::loadActivity,
        insightsProvider = providers::loadInsights
    )

    init {
        styleClass += "shell-life-map-view"
        center = panel.build()
    }

    override fun onShellShutdown() {
        panel.stop()
    }

    companion object {
        private fun resolveUserId(): String {
            return TokenManager.getUserId()
                ?: TokenManager.getUsername()
                ?: "owner"
        }
    }
}
