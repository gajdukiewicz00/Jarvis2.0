package org.jarvis.desktop.e2e.voicetab

import javafx.scene.Node
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.model.VoiceState
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.jarvis.desktop.ui.tabs.VoiceTab
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Third-wave headless coverage for [VoiceTab.mapEventSeverity] — the private
 * `VoiceEventClassifier.Severity -> DesktopRuntimeMonitor.EventSeverity` mapping
 * the tab wires into its event-classifier listener in `init {}`.
 *
 * The sibling [VoiceTabStatusRenderTest] drives connection-phase changes and a
 * single classified backend error, which only exercises `mapEventSeverity`'s
 * ERROR arm (`onError("... connection refused ...")`). The INFO / SUCCESS /
 * WARNING arms are reached only by *session-lifecycle* transitions and by a
 * *non-connection* classified error, which that sibling never produces. Here we
 * drive the tab's PUBLIC [org.jarvis.desktop.service.VoiceControlService] so the
 * classifier emits an event of each remaining severity; every emission fans out
 * to the tab's classifier listener, which maps the severity and forwards it to
 * the injected [DesktopRuntimeMonitor]. We assert the mapped severity by reading
 * the monitor's recorded VOICE events back.
 *
 * SAFETY: identical to the sibling tests — the tab is pointed at a dead endpoint
 * (127.0.0.1:1); we NEVER fire "Start Always Listening" (Alert.showAndWait) or
 * push-to-talk (real microphone); `cleanup()` quiesces the WebSocket reconnect
 * loop up front so background churn cannot inject stray VOICE events, and runs
 * again in `finally`. We assert on the exact event titles our drives produce, so
 * the check is robust to any residual monitor noise.
 */
class VoiceTabEventSeverityTest {

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
                apiGatewayReason = "voice tab event severity test",
                usesManualEndpointOverride = true
            )
        }
    )

    private fun hasVoiceEvent(
        monitor: DesktopRuntimeMonitor,
        severity: DesktopRuntimeMonitor.EventSeverity,
        title: String
    ): Boolean = monitor.currentSnapshot().events.any {
        it.source == DesktopRuntimeMonitor.EventSource.VOICE &&
            it.severity == severity &&
            it.title == title
    }

    /** Quiesce the WS reconnect loop, then plant a clean IDLE baseline for the classifier. */
    private fun quiesce(tab: VoiceTab, root: Node) {
        E2eFx.onFx { tab.cleanup() }
        E2eFx.waitForFx(description = "cleanup settled the status to a disconnected headline") {
            E2eFx.hasText(root, "isconnected")
        }
        // Establishes a non-null `previousVoiceState` in IDLE so the next
        // transition the classifier sees is deterministic.
        E2eFx.onFx { tab.voiceControlService.onSessionStateChanged(VoiceState.IDLE, null) }
    }

    @Test
    fun `session transitions map to INFO and SUCCESS event severities`() {
        val monitor = DesktopRuntimeMonitor()
        val tab = E2eFx.onFx { VoiceTab(deadApiClient(), monitor) }
        try {
            val root = E2eFx.onFx { requireNotNull(tab.tab.content) }
            quiesce(tab, root)

            // IDLE -> LISTENING (not push-to-talk) => classifier INFO "Listening to command".
            E2eFx.onFx { tab.voiceControlService.onSessionStateChanged(VoiceState.LISTENING, "c1") }
            E2eFx.waitForFx(description = "INFO listening event recorded") {
                hasVoiceEvent(monitor, DesktopRuntimeMonitor.EventSeverity.INFO, "Listening to command")
            }

            // LISTENING -> TTS_PLAYBACK => classifier SUCCESS "Jarvis responding".
            E2eFx.onFx { tab.voiceControlService.onSessionStateChanged(VoiceState.TTS_PLAYBACK, "c1") }
            E2eFx.waitForFx(description = "SUCCESS responding event recorded") {
                hasVoiceEvent(monitor, DesktopRuntimeMonitor.EventSeverity.SUCCESS, "Jarvis responding")
            }
        } finally {
            E2eFx.onFx { tab.cleanup() }
        }
    }

    @Test
    fun `a non-connection classified error maps to the WARNING event severity`() {
        val monitor = DesktopRuntimeMonitor()
        val tab = E2eFx.onFx { VoiceTab(deadApiClient(), monitor) }
        try {
            val root = E2eFx.onFx { requireNotNull(tab.tab.content) }
            quiesce(tab, root)

            // "timed out" with no connect/socket keyword classifies as a WARNING
            // (recognition timeout), distinct from the sibling's ERROR path.
            E2eFx.onFx { tab.voiceControlService.onError("Voice recognition timed out") }
            E2eFx.waitForFx(description = "WARNING recognition-timeout event recorded") {
                hasVoiceEvent(monitor, DesktopRuntimeMonitor.EventSeverity.WARNING, "Voice recognition timed out")
            }
            // The render bridge also surfaced the warning headline for the same state.
            assertTrue(E2eFx.hasText(root, "Voice recognition timed out"), "warning headline rendered")
        } finally {
            E2eFx.onFx { tab.cleanup() }
        }
    }
}
