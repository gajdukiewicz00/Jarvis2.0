package org.jarvis.desktop.features.smarthome

import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.layout.BorderPane
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.shell.ShellRouteContent
import org.jarvis.desktop.ui.tabs.DevicesTab

class SmartHomeView(
    apiClient: ApiClient
) : BorderPane(), ShellRouteContent {
    private val legacyDevicesTab = DevicesTab(apiClient)
    private val legacyContent: Node = requireNotNull(legacyDevicesTab.tab.content) {
        "DevicesTab content was not initialized"
    }

    init {
        styleClass += "shell-smart-home-view"
        center = host(legacyContent)
    }

    override fun onRouteActivated() {
        legacyDevicesTab.refresh()
    }

    private fun host(node: Node): Node {
        return if (node is ScrollPane) {
            node.apply {
                isFitToWidth = true
                styleClass += "shell-route-scroll"
            }
        } else {
            ScrollPane(node).apply {
                isFitToWidth = true
                hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
                styleClass += "shell-route-scroll"
            }
        }
    }
}
