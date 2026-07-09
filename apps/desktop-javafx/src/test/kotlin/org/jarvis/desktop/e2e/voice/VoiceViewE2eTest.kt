package org.jarvis.desktop.e2e.voice

import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.voice.VoiceStatusRow
import org.jarvis.desktop.features.voice.VoiceView
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Headless construction + status-row rendering journeys for [VoiceView].
 *
 * Unlike the pure-construction smoke test (which excludes [VoiceView] because
 * its wrapped legacy [org.jarvis.desktop.ui.tabs.VoiceTab] opens a voice
 * WebSocket and enumerates audio devices during construction), this test points
 * the view at a live [MockWebServer]: the WebSocket connect is async and its
 * failure is handled, and audio-device enumeration is wrapped in try/catch and
 * degrades to an empty device list headlessly. No microphone/TTS is exercised
 * and no legacy button (several of which open modal `Alert.showAndWait()`
 * dialogs) is ever fired — only the read-only [VoiceStatusRow] and the shell
 * lifecycle hooks are driven.
 */
class VoiceViewE2eTest {

    private fun buildView(server: MockWebServer): VoiceView = E2eFx.onFx {
        VoiceView(E2eFx.apiClientFor(server), DesktopRuntimeMonitor())
    }

    @Test
    fun `constructs with a coherent voice status row and legacy content`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.start()
        try {
            val view = buildView(server)

            E2eFx.onFx {
                assertNotNull(view, "VoiceView constructed")
                assertNotNull(E2eFx.find<VoiceStatusRow>(view), "top status row present")
                // Every chip's label is prefixed with its channel name, independent of
                // whatever StatusLevel the (async) connection settles on.
                assertTrue(E2eFx.hasText(view, "Connection"), "connection chip rendered")
                assertTrue(E2eFx.hasText(view, "Mic"), "mic chip rendered")
                assertTrue(E2eFx.hasText(view, "STT"), "STT chip rendered")
                assertTrue(E2eFx.hasText(view, "TTS"), "TTS chip rendered")
                assertTrue(E2eFx.hasText(view, "Voice Control"), "legacy voice content mounted")
            }

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `route activation refreshes devices and shutdown cleans up without throwing`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.start()
        try {
            val view = buildView(server)

            // refreshDevices() re-enumerates audio devices on the FX thread; headless
            // it degrades to an empty list rather than touching real hardware.
            E2eFx.onFx { view.onRouteActivated() }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Connection"), "status row still rendered after activation")
            }

            // Shell shutdown tears down the legacy tab (disconnect WS, stop recorder,
            // shutdown session) — must complete without throwing.
            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }
}
