package org.jarvis.desktop.features.voice

import javafx.application.Platform
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
 *
 * Adds a coherent, top-level voice status row ([VoiceStatusRow]) above the
 * legacy content: connection state plus mic/STT/TTS readiness, computed by
 * [VoiceChannelStatusMapper] from the same [org.jarvis.desktop.service.VoiceControlService]
 * the legacy tab already publishes to. This is deliberately read-only —
 * [VoiceControlService.addListener][org.jarvis.desktop.service.VoiceControlService.addListener]
 * is an observer API — so no legacy voice wiring is touched, and the mapper
 * never derives status from free-text errors, which is what keeps this row
 * from ever showing "connected" and "degraded" at the same time.
 */
class VoiceView(
    apiClient: ApiClient,
    runtimeMonitor: DesktopRuntimeMonitor
) : BorderPane(), ShellRouteContent {
    private val legacyVoiceTab = VoiceTab(apiClient, runtimeMonitor)
    private val legacyContent: Node = requireNotNull(legacyVoiceTab.tab.content) {
        "VoiceTab content was not initialized"
    }
    private val statusRow = VoiceStatusRow()

    init {
        styleClass += "shell-voice-view"
        top = statusRow
        center = ScrollPane(legacyContent).apply {
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            styleClass += "shell-route-scroll"
        }
        legacyVoiceTab.voiceControlService.addListener { runtimeState ->
            val status = VoiceChannelStatusMapper.map(runtimeState)
            Platform.runLater { statusRow.render(status) }
        }
    }

    override fun onRouteActivated() {
        legacyVoiceTab.voiceControlService.refreshDevices()
    }

    override fun onShellShutdown() {
        legacyVoiceTab.cleanup()
    }
}
