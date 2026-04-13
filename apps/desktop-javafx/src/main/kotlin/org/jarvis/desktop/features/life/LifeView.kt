package org.jarvis.desktop.features.life

import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.layout.BorderPane
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.shell.ShellRouteContent
import org.jarvis.desktop.ui.tabs.LifeTab

class LifeView(
    apiClient: ApiClient
) : BorderPane(), ShellRouteContent {
    private val legacyLifeTab = LifeTab(apiClient)
    private val legacyContent: Node = requireNotNull(legacyLifeTab.tab.content) {
        "LifeTab content was not initialized"
    }

    init {
        styleClass += "shell-life-view"
        center = host(legacyContent)
    }

    override fun onRouteActivated() {
        legacyLifeTab.refresh()
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
