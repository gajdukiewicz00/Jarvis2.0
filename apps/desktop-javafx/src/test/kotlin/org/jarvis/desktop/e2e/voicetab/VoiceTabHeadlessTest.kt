package org.jarvis.desktop.e2e.voicetab

import javafx.scene.Node
import javafx.scene.control.Button
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.jarvis.desktop.ui.tabs.VoiceTab
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Headless JavaFX coverage for the production voice-control tab [VoiceTab].
 *
 * [VoiceTab] wires a full voice pipeline in its `init {}` — a [VoiceSession]
 * state machine, a [VoiceWebSocketClient], a [VoiceControlService], an
 * [AudioRecorder] and an [AudioPlayer] — then builds the visible widget tree.
 * These tests construct the REAL tab on the FX thread and assert BOTH that the
 * static control surface renders AND that the control-service listener drives
 * the status/device labels (exercising the private `renderVoiceStatus` /
 * `updateState` / `severityColor` paths through the real listener chain).
 *
 * SAFETY:
 *  - The tab is pointed at a dead endpoint (127.0.0.1:1). Its WebSocket
 *    `connect()` is async (OkHttp `newWebSocket`), so construction never
 *    blocks; the connection simply fails/retries on a daemon executor.
 *  - We NEVER fire the "Start Always Listening" button: its handler resolves a
 *    Porcupine key and, when absent (the headless norm), opens a modal
 *    `Alert.showAndWait()` that permanently stalls the FX thread. We also never
 *    fire the push-to-talk press handler, which would open the real microphone
 *    line. Only the non-modal, non-hardware "Refresh devices" action is driven.
 *  - `cleanup()` is called in a finally on every test so the WS reconnect /
 *    session executors don't leak across sibling tests.
 */
class VoiceTabHeadlessTest {

    /** An [ApiClient] pointed at a port where nothing listens — no server lifecycle to manage. */
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
                apiGatewayReason = "voice tab headless test",
                usesManualEndpointOverride = true
            )
        }
    )

    /** Build the real [VoiceTab] on the FX thread. */
    private fun buildTab(): VoiceTab =
        E2eFx.onFx { VoiceTab(deadApiClient(), DesktopRuntimeMonitor()) }

    private fun contentOf(tab: VoiceTab): Node =
        E2eFx.onFx { requireNotNull(tab.tab.content) { "VoiceTab content was not built" } }

    @Test
    fun `voice tab builds its static control surface`() {
        val tab = buildTab()
        try {
            val root = contentOf(tab)
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "Voice Control"), "headline label renders")
                assertTrue(E2eFx.hasText(root, "Transcription:"), "transcription section label renders")
                assertTrue(E2eFx.hasText(root, "Response:"), "response section label renders")
                assertTrue(E2eFx.hasText(root, "Manual Talk"), "instructions/button mention Manual Talk")

                val buttonLabels = E2eFx.findAll<Button>(root).map { it.text }
                assertTrue(buttonLabels.any { it?.contains("Always Listening") == true }, "toggle button present")
                assertTrue(buttonLabels.any { it?.contains("Manual Talk") == true }, "push-to-talk button present")
                assertTrue(buttonLabels.any { it?.contains("Stop") == true || it?.contains("Cancel") == true }, "cancel button present")
                assertTrue(buttonLabels.any { it?.contains("Refresh devices") == true }, "refresh-devices button present")
            }
        } finally {
            E2eFx.onFx { tab.cleanup() }
        }
    }

    @Test
    fun `control service listener renders status and device info during construction`() {
        val tab = buildTab()
        try {
            val root = contentOf(tab)
            // refreshDevices() fires during init -> control-service listener ->
            // Platform.runLater { renderVoiceStatus(...) } which populates the
            // device-info label (either "No audio devices detected" headlessly,
            // or "Mic: ..."/"Out: ..." on a host that exposes audio lines).
            E2eFx.waitForFx(description = "device info label populated by renderVoiceStatus") {
                E2eFx.hasText(root, "No audio devices detected") ||
                    E2eFx.hasText(root, "Mic:") ||
                    E2eFx.hasText(root, "Out:")
            }
        } finally {
            E2eFx.onFx { tab.cleanup() }
        }
    }

    @Test
    fun `refresh devices button re-runs detection without throwing`() {
        val tab = buildTab()
        try {
            val root = contentOf(tab)
            E2eFx.waitForFx(description = "refresh-devices button enabled by status render") {
                E2eFx.findAll<Button>(root)
                    .firstOrNull { it.text?.contains("Refresh devices") == true }
                    ?.isDisable == false
            }
            E2eFx.onFx {
                val refresh = E2eFx.findAll<Button>(root)
                    .first { it.text?.contains("Refresh devices") == true }
                refresh.fire()
            }
            // The re-detection round-trips through the listener again; the device
            // label remains a valid (non-blank) rendering.
            E2eFx.waitForFx(description = "device info still rendered after manual refresh") {
                E2eFx.hasText(root, "No audio devices detected") ||
                    E2eFx.hasText(root, "Mic:") ||
                    E2eFx.hasText(root, "Out:")
            }
        } finally {
            E2eFx.onFx { tab.cleanup() }
        }
    }

    @Test
    fun `cleanup tears down the pipeline without throwing`() {
        val tab = buildTab()
        // cleanup() drives stopAlwaysListening (no-op when never started),
        // audioRecorder.stopRecording (no-op when idle), WS disconnect and
        // session shutdown — none of which open hardware or a modal.
        E2eFx.onFx {
            tab.cleanup()
            assertNotNull(tab.voiceControlService)
        }
    }
}
