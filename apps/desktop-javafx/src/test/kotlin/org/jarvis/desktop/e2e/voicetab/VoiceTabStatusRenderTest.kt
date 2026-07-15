package org.jarvis.desktop.e2e.voicetab

import javafx.scene.Node
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.model.VoiceState
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.jarvis.desktop.ui.tabs.VoiceTab
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Second-wave headless coverage for the private status/severity render paths of
 * [VoiceTab] — `renderVoiceStatus`, `severityColor`, and the event-classifier /
 * runtime listeners it wires in `init {}`.
 *
 * The sibling [VoiceTabHeadlessTest] only exercises the DISCONNECTED headline
 * that construction happens to settle on. Here we drive the tab's PUBLIC
 * [org.jarvis.desktop.service.VoiceControlService] through additional
 * connection phases so `VoiceUxStatus.compute` returns each severity band
 * (INFO / WARNING / ERROR) and both the guidance-visible and guidance-hidden
 * arms of `renderVoiceStatus` run. Each control-service mutation fans out to the
 * three listeners the tab registered — the event classifier (`mapEventSeverity`),
 * the runtime-monitor bridge, and the `renderVoiceStatus` bridge.
 *
 * SAFETY: identical constraints to the sibling test — the tab is pointed at a
 * dead endpoint (127.0.0.1:1), we NEVER fire "Start Always Listening"
 * (Alert.showAndWait) or the push-to-talk press (real microphone), and
 * `cleanup()` runs first to quiesce the WebSocket reconnect churn (it flips
 * `shouldReconnect=false` and emits a final DISCONNECTED) so our subsequent
 * state drives settle deterministically. `onSessionStateChanged(IDLE, null)`
 * clears any `lastError` a pre-cleanup WS failure may have latched, so the
 * connection-phase branch (not the error classifier) decides the headline.
 */
class VoiceTabStatusRenderTest {

    private fun deadApiClient(): ApiClient = ApiClient(
        configProvider = {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = "http://127.0.0.1:1",
                apiBaseUrl = "http://127.0.0.1:1/api/v1",
                voiceWebSocketUrl = "ws://127.0.0.1:1/ws/voice",
                pcControlWebSocketUrl = "ws://127.0.0.1:1/ws/pc-control",
                locale = Locale.ENGLISH,
                voiceLanguage = "en-US",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                apiGatewayReason = "voice tab status render test",
                usesManualEndpointOverride = true
            )
        }
    )

    private fun buildTab(): VoiceTab =
        E2eFx.onFx { VoiceTab(deadApiClient(), DesktopRuntimeMonitor()) }

    private fun contentOf(tab: VoiceTab): Node =
        E2eFx.onFx { requireNotNull(tab.tab.content) { "VoiceTab content was not built" } }

    /** Quiesce the WS reconnect loop, then clear any latched error so the connection phase decides the headline. */
    private fun quiesce(tab: VoiceTab, root: Node) {
        E2eFx.onFx { tab.cleanup() }
        E2eFx.waitForFx(description = "cleanup settled the status to a disconnected headline") {
            E2eFx.hasText(root, "isconnected")
        }
        E2eFx.onFx { tab.voiceControlService.onSessionStateChanged(VoiceState.IDLE, null) }
    }

    @Test
    fun `reconnecting phase renders the warning headline with no guidance`() {
        val tab = buildTab()
        try {
            val root = contentOf(tab)
            quiesce(tab, root)
            // RECONNECTING -> connectionProblem(WARNING) with guidance == null, so
            // renderVoiceStatus takes the guidance-hidden branch (isVisible=false).
            E2eFx.onFx {
                tab.voiceControlService.onConnectionStateChanged("Reconnecting in 5s (attempt 1/5)...")
            }
            E2eFx.waitForFx(description = "reconnecting warning headline") {
                E2eFx.hasText(root, "Reconnecting to voice gateway")
            }
        } finally {
            E2eFx.onFx { tab.cleanup() }
        }
    }

    @Test
    fun `connecting phase renders the info headline`() {
        val tab = buildTab()
        try {
            val root = contentOf(tab)
            quiesce(tab, root)
            // CONNECTING -> connectionProblem(INFO), exercising the INFO arm of severityColor.
            E2eFx.onFx {
                tab.voiceControlService.onConnectionStateChanged("CONNECTING")
            }
            E2eFx.waitForFx(description = "connecting info headline") {
                E2eFx.hasText(root, "Connecting to voice gateway")
            }
        } finally {
            E2eFx.onFx { tab.cleanup() }
        }
    }

    @Test
    fun `classified backend error renders the error headline with guidance visible`() {
        val tab = buildTab()
        try {
            val root = contentOf(tab)
            quiesce(tab, root)
            // A classified error takes priority over the connection phase: it maps to
            // the ERROR severity band AND supplies guidance, so renderVoiceStatus runs
            // the guidance-visible branch and severityColor's ERROR arm.
            E2eFx.onFx {
                tab.voiceControlService.onError("Connection refused by voice gateway")
            }
            E2eFx.waitForFx(description = "error headline") {
                E2eFx.hasText(root, "Voice backend unavailable")
            }
            E2eFx.waitForFx(description = "guidance text surfaced for the error") {
                E2eFx.hasText(root, "voice gateway is running")
            }
        } finally {
            E2eFx.onFx { tab.cleanup() }
        }
    }
}
