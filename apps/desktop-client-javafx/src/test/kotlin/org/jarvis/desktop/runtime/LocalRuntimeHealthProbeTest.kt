package org.jarvis.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LocalRuntimeHealthProbeTest {

    private val clock = Clock.fixed(Instant.parse("2026-03-14T10:15:30Z"), ZoneOffset.UTC)

    @Test
    fun `healthy actuator response is marked connected`() {
        val probe = LocalRuntimeHealthProbe(
            apiGatewayBaseUrlProvider = { "http://127.0.0.1:8080" },
            fetcher = { _: URI -> LocalRuntimeHealthProbe.HttpResult(200, """{"status":"UP"}""") },
            clock = clock
        )

        val status = probe.probe()

        assertEquals(DesktopRuntimeMonitor.ConnectionState.CONNECTED, status.state)
        assertEquals("Local runtime healthy at http://127.0.0.1:8080/actuator/health", status.detail)
    }

    @Test
    fun `unhealthy response is marked degraded`() {
        val probe = LocalRuntimeHealthProbe(
            apiGatewayBaseUrlProvider = { "http://127.0.0.1:8080" },
            fetcher = { _: URI -> LocalRuntimeHealthProbe.HttpResult(200, """{"status":"DOWN"}""") },
            clock = clock
        )

        val status = probe.probe()

        assertEquals(DesktopRuntimeMonitor.ConnectionState.DEGRADED, status.state)
    }

    @Test
    fun `connection error is marked error`() {
        val probe = LocalRuntimeHealthProbe(
            apiGatewayBaseUrlProvider = { "http://127.0.0.1:8080" },
            fetcher = { _: URI -> throw IllegalStateException("connection refused") },
            clock = clock
        )

        val status = probe.probe()

        assertEquals(DesktopRuntimeMonitor.ConnectionState.ERROR, status.state)
        assertEquals("Runtime unreachable: connection refused", status.detail)
    }

    @Test
    fun `probe uses the latest configured base URL on each invocation`() {
        var baseUrl = "http://127.0.0.1:8080"
        val seenUris = mutableListOf<URI>()
        val probe = LocalRuntimeHealthProbe(
            apiGatewayBaseUrlProvider = { baseUrl },
            fetcher = { uri ->
                seenUris += uri
                LocalRuntimeHealthProbe.HttpResult(200, """{"status":"UP"}""")
            },
            clock = clock
        )

        probe.probe()
        baseUrl = "https://api.jarvis.local"
        probe.probe()

        assertEquals(
            listOf(
                URI.create("http://127.0.0.1:8080/actuator/health"),
                URI.create("https://api.jarvis.local/actuator/health")
            ),
            seenUris
        )
    }
}
