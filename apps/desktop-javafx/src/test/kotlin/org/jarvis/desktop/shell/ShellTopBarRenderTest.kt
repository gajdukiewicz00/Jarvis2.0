package org.jarvis.desktop.shell

import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.agent.status.StatusAggregator
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.panic.PanicControlService
import org.jarvis.desktop.features.status.ServiceStatusReadModel
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Focused branch coverage for [ShellTopBar]'s render* methods and the panic
 * toggle, driven directly (the shell owner normally pushes these snapshots in).
 *
 * These are the branches the navigation E2E journeys never exercise: the full
 * backend connection-state -> pill-text/tone `when`, the services-pill tone
 * ladder (down/degraded/up/empty) with its two tooltip shapes, config/profile
 * rendering, and the panic engage/clear round-trip against a MockWebServer.
 *
 * The bar is built on the FX thread and always [ShellTopBar.dispose]-d to stop
 * its panic worker and detach the navigator listener.
 */
class ShellTopBarRenderTest {

    private fun status(state: DesktopRuntimeMonitor.ConnectionState) =
        DesktopRuntimeMonitor.ConnectionStatus(state, "detail", Instant.now())

    private fun snapshot(
        backend: DesktopRuntimeMonitor.ConnectionState,
        errorEvents: Int = 0
    ): DesktopRuntimeMonitor.Snapshot {
        val events = (0 until errorEvents).map {
            DesktopRuntimeMonitor.RuntimeEvent(
                source = DesktopRuntimeMonitor.EventSource.SYSTEM,
                severity = DesktopRuntimeMonitor.EventSeverity.ERROR,
                title = "boom-$it",
                details = "",
                timestamp = Instant.now()
            )
        }
        return DesktopRuntimeMonitor.Snapshot(
            backend = status(backend),
            voice = status(DesktopRuntimeMonitor.ConnectionState.UNKNOWN),
            pcControl = status(DesktopRuntimeMonitor.ConnectionState.UNKNOWN),
            voiceRuntime = null,
            events = events
        )
    }

    private fun svc(name: String, status: StatusAggregator.ProbeStatus) =
        StatusAggregator.ServiceStatus(name, status, "detail-$name")

    private fun serviceSnapshot(services: List<StatusAggregator.ServiceStatus>) =
        ServiceStatusReadModel.Snapshot(
            refreshedAt = Instant.now(),
            baseUrl = "http://127.0.0.1:1",
            services = services
        )

    private fun backendPill(bar: ShellTopBar): Label =
        E2eFx.findAll<Label>(bar).first { it.text?.startsWith("Backend") == true }

    private fun servicesPill(bar: ShellTopBar): Label =
        E2eFx.findAll<Label>(bar).first { it.text?.startsWith("Services:") == true }

    private fun buildBar(panic: PanicControlService? = null): ShellTopBar {
        val navigator = ShellNavigator(ShellRoute.CONTROL_CENTER)
        return if (panic != null) ShellTopBar(navigator, panicControlService = panic) else ShellTopBar(navigator)
    }

    @Test
    fun `renderRuntime maps every backend connection state onto its pill text and tone`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val bar = E2eFx.onFx { buildBar() }
        try {
            val cases = listOf(
                DesktopRuntimeMonitor.ConnectionState.CONNECTED to ("Backend healthy" to "shell-status-tone-success"),
                DesktopRuntimeMonitor.ConnectionState.CONNECTING to ("Backend connecting" to "shell-status-tone-muted"),
                DesktopRuntimeMonitor.ConnectionState.DEGRADED to ("Backend degraded" to "shell-status-tone-warning"),
                DesktopRuntimeMonitor.ConnectionState.DISCONNECTED to ("Backend offline" to "shell-status-tone-error"),
                DesktopRuntimeMonitor.ConnectionState.ERROR to ("Backend error" to "shell-status-tone-error"),
                DesktopRuntimeMonitor.ConnectionState.UNKNOWN to ("Backend unknown" to "shell-status-tone-muted")
            )
            cases.forEach { (state, expected) ->
                val (text, tone) = expected
                E2eFx.onFx { bar.renderRuntime(snapshot(state)) }
                E2eFx.onFx {
                    val pill = backendPill(bar)
                    assertEquals(text, pill.text, "pill text for $state")
                    assertTrue(pill.styleClass.contains(tone), "tone class for $state should be $tone")
                    // Exactly one tone class is present at a time (old ones stripped).
                    assertEquals(1, pill.styleClass.count { it.startsWith("shell-status-tone-") })
                }
            }
        } finally {
            E2eFx.onFx { bar.dispose() }
        }
    }

    @Test
    fun `renderRuntime counts only ERROR events in the alerts label`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val bar = E2eFx.onFx { buildBar() }
        try {
            E2eFx.onFx { bar.renderRuntime(snapshot(DesktopRuntimeMonitor.ConnectionState.CONNECTED, errorEvents = 3)) }
            E2eFx.onFx { assertTrue(E2eFx.hasText(bar, "Alerts: 3"), "alerts label should count the 3 ERROR events") }
        } finally {
            E2eFx.onFx { bar.dispose() }
        }
    }

    @Test
    fun `renderServiceStatus down beats degraded and lists offenders in the tooltip`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val bar = E2eFx.onFx { buildBar() }
        try {
            E2eFx.onFx {
                bar.renderServiceStatus(
                    serviceSnapshot(
                        listOf(
                            svc("api-gateway", StatusAggregator.ProbeStatus.UP),
                            svc("brain", StatusAggregator.ProbeStatus.DEGRADED),
                            svc("memory", StatusAggregator.ProbeStatus.DOWN)
                        )
                    )
                )
            }
            E2eFx.onFx {
                val pill = servicesPill(bar)
                assertEquals("Services: 1/3 up", pill.text)
                // DOWN present -> error tone wins over the degraded one.
                assertTrue(pill.styleClass.contains("shell-status-tone-error"))
                val tip = pill.tooltip?.text ?: ""
                assertTrue(tip.contains("memory") && tip.contains("brain"), "tooltip lists the down/degraded services")
                assertTrue(tip.contains("Click to open Service Status"))
            }
        } finally {
            E2eFx.onFx { bar.dispose() }
        }
    }

    @Test
    fun `renderServiceStatus uses the degraded tone when nothing is fully down`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val bar = E2eFx.onFx { buildBar() }
        try {
            E2eFx.onFx {
                bar.renderServiceStatus(
                    serviceSnapshot(
                        listOf(
                            svc("api-gateway", StatusAggregator.ProbeStatus.UP),
                            svc("brain", StatusAggregator.ProbeStatus.DEGRADED)
                        )
                    )
                )
            }
            E2eFx.onFx {
                val pill = servicesPill(bar)
                assertEquals("Services: 1/2 up", pill.text)
                assertTrue(pill.styleClass.contains("shell-status-tone-warning"))
            }
        } finally {
            E2eFx.onFx { bar.dispose() }
        }
    }

    @Test
    fun `renderServiceStatus shows an all-clear tooltip and up tone when every service is healthy`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val bar = E2eFx.onFx { buildBar() }
        try {
            E2eFx.onFx {
                bar.renderServiceStatus(
                    serviceSnapshot(
                        listOf(
                            svc("api-gateway", StatusAggregator.ProbeStatus.UP),
                            svc("brain", StatusAggregator.ProbeStatus.PROTECTED)
                        )
                    )
                )
            }
            E2eFx.onFx {
                val pill = servicesPill(bar)
                assertEquals("Services: 2/2 up", pill.text)
                assertTrue(pill.styleClass.contains("shell-status-tone-success"))
                assertTrue(pill.tooltip?.text?.contains("All 2 service(s) reachable") == true)
            }
        } finally {
            E2eFx.onFx { bar.dispose() }
        }
    }

    @Test
    fun `renderServiceStatus falls back to the unknown tone when there are no services`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val bar = E2eFx.onFx { buildBar() }
        try {
            E2eFx.onFx { bar.renderServiceStatus(serviceSnapshot(emptyList())) }
            E2eFx.onFx {
                val pill = servicesPill(bar)
                assertEquals("Services: 0/0 up", pill.text)
                assertTrue(pill.styleClass.contains("shell-status-tone-muted"), "empty list => UNKNOWN tone")
                assertTrue(pill.tooltip?.text?.contains("All 0 service(s) reachable") == true)
            }
        } finally {
            E2eFx.onFx { bar.dispose() }
        }
    }

    @Test
    fun `renderConfig surfaces the gateway endpoint and profile`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val server = MockWebServer().also { it.start() }
        val bar = E2eFx.onFx { buildBar() }
        try {
            val config = E2eFx.configFor(server)()
            E2eFx.onFx { bar.renderConfig(config) }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(bar, config.apiGatewayBaseUrl), "endpoint label should show the gateway URL")
            }
        } finally {
            E2eFx.onFx { bar.dispose() }
            server.shutdown()
        }
    }

    @Test
    fun `firing the panic toggle engages then clears against the backend`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val server = MockWebServer()
        // engage POST -> engaged=true ; clear POST -> engaged=false
        server.enqueue(MockResponse().setBody("""{"engaged":true,"actor":"desktop-shell","reason":"drill"}"""))
        server.enqueue(MockResponse().setBody("""{"engaged":false,"actor":"desktop-shell"}"""))
        server.start()
        val panic = PanicControlService(E2eFx.apiClientFor(server))
        val bar = E2eFx.onFx { buildBar(panic) }
        try {
            val panicButton = E2eFx.onFx {
                E2eFx.findAll<ToggleButton>(bar).first { it.text == "Panic" }
            }

            // User clicks Panic -> ToggleButton flips to selected -> engage() -> renderPanic.
            // Wait for the engaged render AND for the worker's finally to re-enable the button
            // (a disabled ToggleButton.fire() is a no-op, which would stall the next click).
            E2eFx.onFx { panicButton.fire() }
            E2eFx.waitForFx(description = "panic engaged and re-enabled") {
                panicButton.text == "Panic engaged" && panicButton.isSelected && !panicButton.isDisable
            }

            // Click again -> flips off -> clear() -> renderPanic back to idle.
            E2eFx.onFx { panicButton.fire() }
            E2eFx.waitForFx(description = "panic cleared and re-enabled") {
                panicButton.text == "Panic" && !panicButton.isSelected && !panicButton.isDisable
            }
        } finally {
            E2eFx.onFx { bar.dispose() }
            server.shutdown()
        }
    }
}
