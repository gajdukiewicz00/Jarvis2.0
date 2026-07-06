package org.jarvis.desktop.features.smarthome

import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.shell.ShellRouteContent
import org.jarvis.desktop.ui.tabs.DevicesTab

/**
 * Smart Home route — hosts the legacy [DevicesTab] device list plus the
 * [IntentView] (natural-language command box) and [ScenesView] sections
 * (list / create / activate / delete scenes) in a single scrollable page,
 * mirroring the outer-ScrollPane + inner `shell-*-view` card layout used by
 * [org.jarvis.desktop.features.planner.PlannerView].
 */
class SmartHomeView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val legacyDevicesTab = DevicesTab(apiClient)
    private val legacyContent: Node = requireNotNull(legacyDevicesTab.tab.content) {
        "DevicesTab content was not initialized"
    }
    private val intentView = IntentView(apiClient)
    private val scenesView = ScenesView(apiClient)

    init {
        styleClass += "shell-route-scroll"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = VBox(18.0).apply {
            styleClass += "shell-smart-home-view"
            padding = Insets(24.0)
            children += unwrap(legacyContent)
            children += intentView
            children += scenesView
        }
    }

    override fun onRouteActivated() {
        legacyDevicesTab.refresh()
        scenesView.refresh()
    }

    override fun onShellShutdown() {
        intentView.shutdown()
        scenesView.shutdown()
    }

    private fun unwrap(node: Node): Node {
        return if (node is ScrollPane) node.content else node
    }
}
