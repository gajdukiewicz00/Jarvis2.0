package org.jarvis.desktop.service.wake

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Closeable

/**
 * Fake [WakeSidecarClient] — records what the provider sent and lets the test
 * push SSE payloads through the captured [onEvent] sink. No real network.
 */
class FakeWakeSidecarClient(
    var startResponse: StartEngineResponse = StartEngineResponse(true, 200, null),
    var diagnosticsData: SidecarDiagnosticsData? = null,
    private val healthProvider: () -> Boolean = { true }
) : WakeSidecarClient {

    val startRequests = mutableListOf<StartEngineRequest>()
    var healthCalls = 0
    var stopCalls = 0
    var streamClosed = false
    private var eventSink: ((String) -> Unit)? = null
    private var errorSink: ((Throwable) -> Unit)? = null

    override fun health(): SidecarHealth {
        healthCalls++
        return SidecarHealth(healthProvider())
    }

    override fun devices(): List<String> = emptyList()

    override fun startEngine(request: StartEngineRequest): StartEngineResponse {
        startRequests += request
        return startResponse
    }

    override fun stopEngine(): Boolean {
        stopCalls++
        return true
    }

    override fun openEvents(onEvent: (String) -> Unit, onError: (Throwable) -> Unit): Closeable {
        eventSink = onEvent
        errorSink = onError
        return Closeable { streamClosed = true }
    }

    override fun diagnostics(): SidecarDiagnosticsData? = diagnosticsData

    /** Simulate the sidecar pushing one `data:` JSON payload. */
    fun emit(json: String) = eventSink?.invoke(json)

    fun emitError(t: Throwable) = errorSink?.invoke(t)
}

class OpenWakeWordProviderTest {

    private val config = WakeWordConfig()

    @Test
    fun `start posts the openwakeword engine and returns started`() {
        val sidecar = FakeWakeSidecarClient()
        val provider = OpenWakeWordProvider(http = sidecar)

        val result = provider.start(config) { }

        assertTrue(result.started)
        assertEquals("openwakeword", result.providerId)
        assertEquals(WakeProviderState.READY, result.status)
        assertEquals(1, sidecar.startRequests.size)
        assertEquals("openwakeword", sidecar.startRequests.single().engine)
        assertEquals(config.model, sidecar.startRequests.single().model)
    }

    @Test
    fun `WAKE_DETECTED SSE line fires the callback with parsed score and model`() {
        val sidecar = FakeWakeSidecarClient()
        val provider = OpenWakeWordProvider(http = sidecar)
        val events = mutableListOf<WakeEvent>()

        provider.start(config) { events += it }
        sidecar.emit("""{"event":"WAKE_DETECTED","model":"hey_jarvis","score":0.87,"device":"mic0"}""")

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("openwakeword", event.provider)
        assertEquals("hey_jarvis", event.model)
        assertEquals(0.87, event.score, 1e-9)
        assertEquals("mic0", event.device)
    }

    @Test
    fun `non-wake SSE lines and garbage are ignored`() {
        val sidecar = FakeWakeSidecarClient()
        val provider = OpenWakeWordProvider(http = sidecar)
        val events = mutableListOf<WakeEvent>()

        provider.start(config) { events += it }
        sidecar.emit("""{"event":"HEARTBEAT"}""")
        sidecar.emit("not-json-at-all")

        assertTrue(events.isEmpty())
    }

    @Test
    fun `stop posts stop and closes the event stream`() {
        val sidecar = FakeWakeSidecarClient()
        val provider = OpenWakeWordProvider(http = sidecar)

        provider.start(config) { }
        provider.stop()

        assertEquals(1, sidecar.stopCalls)
        assertTrue(sidecar.streamClosed)
    }

    @Test
    fun `probeAvailable is false when health is down and autostart cannot help`() {
        val sidecar = FakeWakeSidecarClient(healthProvider = { false })
        val provider = OpenWakeWordProvider(http = sidecar, autostart = { false })

        assertFalse(provider.probeAvailable())
    }

    @Test
    fun `start ensures health via autostart then poll when initially down`() {
        var calls = 0
        // Down for the first two health calls, up afterwards → forces one poll sleep.
        val sidecar = FakeWakeSidecarClient(healthProvider = { calls++ >= 2 })
        var clock = 0L
        var autostarted = false
        val provider = OpenWakeWordProvider(
            http = sidecar,
            autostart = { autostarted = true; true },
            nowMs = { clock },
            sleepMs = { clock += it }
        )

        val result = provider.start(config) { }

        assertTrue(autostarted)
        assertTrue(result.started)
        assertTrue(clock >= SidecarWakeProvider.HEALTH_POLL_INTERVAL_MS) // at least one poll sleep happened
    }

    @Test
    fun `start reports sidecar_unreachable when health never comes up`() {
        val sidecar = FakeWakeSidecarClient(healthProvider = { false })
        val provider = OpenWakeWordProvider(
            http = sidecar,
            autostart = { false },
            nowMs = { 0L },
            sleepMs = { }
        )

        val result = provider.start(config) { }

        assertFalse(result.started)
        assertEquals(WakeProviderState.UNAVAILABLE, result.status)
        assertNotNull(result.reason)
        assertTrue(result.reason!!.contains("sidecar_unreachable"))
    }

    @Test
    fun `diagnostics reflect the sidecar report`() {
        val sidecar = FakeWakeSidecarClient(
            diagnosticsData = SidecarDiagnosticsData(
                installed = true,
                models = listOf("hey_jarvis"),
                selectedDevice = "mic0",
                listening = true,
                lastWakeScore = 0.9,
                lastWakeDetectedAt = "2026-07-15T00:00:00Z",
                lastError = null
            )
        )
        val provider = OpenWakeWordProvider(http = sidecar)
        provider.start(config) { }

        val diag = provider.diagnostics()
        assertEquals("openwakeword", diag.providerId)
        assertEquals(true, diag.installed)
        assertEquals(listOf("hey_jarvis"), diag.models)
        assertTrue(diag.toJson().contains("openwakeword"))
    }
}
