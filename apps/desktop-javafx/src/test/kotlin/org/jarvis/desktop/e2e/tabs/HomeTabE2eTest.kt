package org.jarvis.desktop.e2e.tabs

import javafx.scene.Node
import javafx.scene.control.Button
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.model.VoiceRuntimeState
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor.ConnectionState
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor.ConnectionStatus
import org.jarvis.desktop.ui.tabs.HomeTab
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Headless UI journeys for [HomeTab]. HomeTab has no backend of its own — it
 * subscribes to a [DesktopRuntimeMonitor] and re-renders (on the FX thread via
 * Platform.runLater) whenever a new snapshot is published. Each test builds the
 * real tab, drives the monitor, and asserts the visible label/text-area tree
 * reacted. The only control ("Refresh runtime") delegates to a plain callback,
 * so nothing here opens a modal dialog.
 */
class HomeTabE2eTest {

    private fun status(state: ConnectionState): ConnectionStatus =
        ConnectionStatus(state = state, detail = "detail-${state.name}", updatedAt = Instant.now())

    /** Build the real HomeTab on the FX thread and return its content root. */
    private fun buildContent(monitor: DesktopRuntimeMonitor, onRefresh: () -> Unit = {}): Node =
        E2eFx.onFx { HomeTab(monitor, onRefresh).tab.content }

    @Test
    fun `initial snapshot renders checking state and account line`() {
        val monitor = DesktopRuntimeMonitor()
        val content = buildContent(monitor)

        // The addListener callback fires the first render via Platform.runLater.
        E2eFx.waitForFx(description = "initial checking state") {
            E2eFx.hasText(content, "Checking Jarvis runtime")
        }
        assertTrue(E2eFx.onFx { E2eFx.hasText(content, "Signed in as") }, "account line should render")
        assertTrue(
            E2eFx.onFx { E2eFx.hasText(content, "No assistant activity yet") },
            "empty events placeholder should render"
        )
    }

    @Test
    fun `all channels connected renders ready and event feed`() {
        val monitor = DesktopRuntimeMonitor()
        val content = buildContent(monitor)

        E2eFx.waitForFx(description = "initial render") { E2eFx.hasText(content, "Checking") }

        monitor.updateBackend(status(ConnectionState.CONNECTED))
        monitor.updateVoice(status(ConnectionState.CONNECTED))
        monitor.updatePcControl(status(ConnectionState.CONNECTED))

        E2eFx.waitForFx(description = "overall ready") {
            E2eFx.hasText(content, "Ready: runtime, voice, and desktop actions are connected")
        }
        assertTrue(E2eFx.onFx { E2eFx.hasText(content, "Connected") }, "per-channel labels read Connected")

        monitor.recordEvent(
            source = DesktopRuntimeMonitor.EventSource.ASSISTANT,
            severity = DesktopRuntimeMonitor.EventSeverity.SUCCESS,
            title = "Turned on the kitchen lights",
            details = "handled locally"
        )
        E2eFx.waitForFx(description = "event surfaced in feed") {
            E2eFx.hasText(content, "Turned on the kitchen lights")
        }
    }

    @Test
    fun `overall state text tracks the degraded, error, disconnected and connecting branches`() {
        val monitor = DesktopRuntimeMonitor()
        val content = buildContent(monitor)
        E2eFx.waitForFx(description = "initial render") { E2eFx.hasText(content, "Checking") }

        monitor.updateBackend(status(ConnectionState.ERROR))
        E2eFx.waitForFx(description = "error branch") { E2eFx.hasText(content, "Attention needed") }

        monitor.updateBackend(status(ConnectionState.DISCONNECTED))
        E2eFx.waitForFx(description = "disconnected branch") {
            E2eFx.hasText(content, "Disconnected: backend is not currently reachable")
        }

        monitor.updateBackend(status(ConnectionState.DEGRADED))
        E2eFx.waitForFx(description = "degraded branch") {
            E2eFx.hasText(content, "Degraded: Jarvis is reachable")
        }

        monitor.updateBackend(status(ConnectionState.CONNECTING))
        E2eFx.waitForFx(description = "connecting branch") {
            E2eFx.hasText(content, "Starting up: Jarvis is still connecting")
        }
    }

    @Test
    fun `voice runtime snapshot drives the voice label via VoiceUxStatus`() {
        val monitor = DesktopRuntimeMonitor()
        val content = buildContent(monitor)
        E2eFx.waitForFx(description = "initial render") { E2eFx.hasText(content, "Checking") }

        monitor.updateVoiceRuntime(VoiceRuntimeState.disconnected(Instant.now()))

        E2eFx.waitForFx(description = "voice ux headline") {
            E2eFx.hasText(content, "Voice gateway disconnected")
        }
    }

    @Test
    fun `refresh runtime button invokes the callback`() {
        val monitor = DesktopRuntimeMonitor()
        val fired = AtomicInteger(0)
        val content = buildContent(monitor) { fired.incrementAndGet() }
        E2eFx.waitForFx(description = "initial render") { E2eFx.hasText(content, "Checking") }

        E2eFx.onFx {
            val button = requireNotNull(E2eFx.find<Button>(content)) { "Refresh runtime button not found" }
            button.fire()
        }
        assertTrue(fired.get() >= 1, "onRefreshRuntime callback should be invoked by the button")
    }
}
