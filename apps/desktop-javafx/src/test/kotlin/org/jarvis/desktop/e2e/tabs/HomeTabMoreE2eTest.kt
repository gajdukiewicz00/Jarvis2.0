package org.jarvis.desktop.e2e.tabs

import javafx.scene.Node
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.model.VoiceRuntimeState
import org.jarvis.desktop.model.VoiceRuntimeState.AudioDeviceInfo
import org.jarvis.desktop.model.VoiceRuntimeState.ConnectionPhase
import org.jarvis.desktop.model.VoiceState
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.jarvis.desktop.ui.tabs.HomeTab
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Second-wave headless coverage for [HomeTab.describeVoice]. The primary suite
 * only feeds a `disconnected()` runtime (no input device, no guidance), so the
 * `inputDevice?.let { "mic: …" }` and `status.guidance?.let { … }` arms of
 * `describeVoice` never run. Here we publish a CONNECTED runtime that carries a
 * usable input device and an IDLE session (which yields guidance), so both
 * optional segments are appended to the voice label.
 */
class HomeTabMoreE2eTest {

    private fun buildContent(monitor: DesktopRuntimeMonitor): Node =
        E2eFx.onFx { HomeTab(monitor).tab.content }

    @Test
    fun `voice label appends mic name and guidance when a device and idle session are present`() {
        val monitor = DesktopRuntimeMonitor()
        val content = buildContent(monitor)
        E2eFx.waitForFx(description = "initial render") { E2eFx.hasText(content, "Checking") }

        // CONNECTED + a usable input device clears the connection/device problem
        // branches so VoiceUxStatus falls through to the IDLE session status,
        // which supplies guidance ("Enable always-listening or use push-to-talk").
        val runtime = VoiceRuntimeState(
            sessionState = VoiceState.IDLE,
            connectionPhase = ConnectionPhase.CONNECTED,
            pushToTalkActive = false,
            alwaysListeningActive = false,
            currentCorrelationId = null,
            inputDevice = AudioDeviceInfo("Studio Mic", available = true),
            outputDevice = null,
            lastError = null,
            updatedAt = Instant.now()
        )
        monitor.updateVoiceRuntime(runtime)

        E2eFx.waitForFx(description = "voice label shows the mic name segment") {
            E2eFx.hasText(content, "mic: Studio Mic")
        }
        assertTrue(
            E2eFx.onFx {
                E2eFx.hasText(content, "Voice inactive") &&
                    E2eFx.hasText(content, "Enable always-listening")
            },
            "the idle headline and its guidance segment should both render in the voice label"
        )
    }
}
