package org.jarvis.desktop.features.pccontrol

import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.layout.BorderPane
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.shell.ShellRouteContent
import org.jarvis.desktop.ui.tabs.PcControlTab

class PcControlView(
    apiClient: ApiClient
) : BorderPane(), ShellRouteContent {
    private val legacyPcControlTab = PcControlTab(apiClient)
    private val legacyContent: Node = requireNotNull(legacyPcControlTab.tab.content) {
        "PcControlTab content was not initialized"
    }

    init {
        styleClass += "shell-pc-control-view"
        center = host(legacyContent)
    }

    override fun onRouteActivated() {
        legacyPcControlTab.refresh()
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
