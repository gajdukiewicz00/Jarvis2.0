package org.jarvis.desktop.features.voice

import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.layout.BorderPane
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.jarvis.desktop.shell.ShellRouteContent
import org.jarvis.desktop.ui.tabs.VoiceTab

/**
 * Temporary adapter that lets the unified shell host the legacy voice screen
 * while lifecycle and cleanup are managed by the shell instead of the old tabs
 * container.
 */
class VoiceView(
    apiClient: ApiClient,
    runtimeMonitor: DesktopRuntimeMonitor
) : BorderPane(), ShellRouteContent {
    private val legacyVoiceTab = VoiceTab(apiClient, runtimeMonitor)
    private val legacyContent: Node = requireNotNull(legacyVoiceTab.tab.content) {
        "VoiceTab content was not initialized"
    }

    init {
        styleClass += "shell-voice-view"
        center = ScrollPane(legacyContent).apply {
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            styleClass += "shell-route-scroll"
        }
    }

    override fun onRouteActivated() {
        legacyVoiceTab.voiceControlService.refreshDevices()
    }

    override fun onShellShutdown() {
        legacyVoiceTab.cleanup()
    }
}
