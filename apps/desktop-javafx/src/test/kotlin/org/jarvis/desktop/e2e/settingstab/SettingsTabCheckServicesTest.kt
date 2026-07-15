package org.jarvis.desktop.e2e.settingstab

import javafx.scene.Node
import javafx.scene.control.Button
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.ui.tabs.SettingsTab
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Third-wave coverage for [SettingsTab]'s "🔍 Check All Services" handler
 * (`checkServiceStatus`), which the sibling [SettingsTabHeadlessTest]
 * deliberately does not drive.
 *
 * `checkServiceStatus` runs a SYNCHRONOUS prelude on the FX thread — it flips the
 * status label to "Checking services..." — then hands the actual probing to a
 * background daemon thread. We fire the button and assert the synchronous label
 * flip WITHIN THE SAME FX pulse, before the daemon's terminal
 * `Platform.runLater { ... "✓ Service check complete" }` can run. This
 * deterministically covers the handler's entry branch (and starts the daemon
 * that exercises the probe loop) without asserting the non-deterministic network
 * result the sibling test flagged.
 *
 * SAFETY: no modal, no persistence. The probe daemon is a daemon thread with
 * bounded (2–3s) per-probe timeouts, so it cannot hang or outlive the JVM; we
 * never join it and never assert its outcome.
 */
class SettingsTabCheckServicesTest {

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
                apiGatewayReason = "settings tab check-services test",
                usesManualEndpointOverride = true
            )
        }
    )

    private fun buildTab(): SettingsTab = E2eFx.onFx { SettingsTab(deadApiClient(), {}) }

    private fun contentOf(tab: SettingsTab): Node =
        E2eFx.onFx { requireNotNull(tab.tab.content) { "SettingsTab content was not built" } }

    @Test
    fun `check all services flips the status label synchronously before the daemon probes`() {
        val tab = buildTab()
        val root = contentOf(tab)
        E2eFx.onFx {
            val checkBtn = E2eFx.findAll<Button>(root)
                .first { it.text?.contains("Check All Services") == true }

            // fire() runs the handler on the FX thread: it sets the status label,
            // then starts the daemon. We are still holding the FX pulse, so the
            // daemon's terminal Platform.runLater has NOT executed yet.
            checkBtn.fire()

            assertTrue(
                E2eFx.hasText(root, "Checking services"),
                "status label reflects the in-progress check set by the synchronous prelude"
            )
        }
    }
}
