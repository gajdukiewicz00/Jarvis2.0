package org.jarvis.desktop.features.life

import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.layout.BorderPane
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.shell.ShellRouteContent
import org.jarvis.desktop.ui.tabs.LifeTab

/**
 * Legacy Life view kept for ad-hoc dev use only.
 *
 * <p>The shell routes the LIFE entry to {@link LifeMapView} now. This class
 * is no longer instantiated by {@code ShellRoot} and exists so engineers
 * can compare the new Life Map against the legacy expense form / time-
 * tracking buttons during the migration. Delete once the migration sticks.</p>
 */
@Deprecated("Replaced by LifeMapView; kept for dev parity only.")
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
