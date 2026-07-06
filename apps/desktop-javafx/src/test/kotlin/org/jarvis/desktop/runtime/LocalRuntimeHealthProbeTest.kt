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
        assertEquals("Local runtime healthy at http://127.0.0.1:8080/actuator/health/readiness", status.detail)
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
                URI.create("http://127.0.0.1:8080/actuator/health/readiness"),
                URI.create("https://api.jarvis.local/actuator/health/readiness")
            ),
            seenUris
        )
    }

    @Test
    fun `protected readiness response is connected in k8s runtime mode`() {
        val probe = LocalRuntimeHealthProbe(
            apiGatewayBaseUrlProvider = { "http://127.0.0.1:8080" },
            runtimeModeProvider = { "k8s" },
            fetcher = { _: URI -> LocalRuntimeHealthProbe.HttpResult(401, "") },
            clock = clock
        )

        val status = probe.probe()

        assertEquals(DesktopRuntimeMonitor.ConnectionState.CONNECTED, status.state)
    }

    @Test
    fun `protected legacy health response is connected in k8s runtime mode`() {
        val probe = LocalRuntimeHealthProbe(
            apiGatewayBaseUrlProvider = { "http://127.0.0.1:8080" },
            runtimeModeProvider = { "K8S" },
            fetcher = { uri ->
                when (uri.path) {
                    "/actuator/health/readiness" -> LocalRuntimeHealthProbe.HttpResult(404, "")
                    "/actuator/health" -> LocalRuntimeHealthProbe.HttpResult(403, "")
                    else -> error("Unexpected URI $uri")
                }
            },
            clock = clock
        )

        val status = probe.probe()

        assertEquals(DesktopRuntimeMonitor.ConnectionState.CONNECTED, status.state)
    }

    @Test
    fun `kubectl-unavailable style errors do not matter when the gateway probe itself is green`() {
        // This probe never shells out to kubectl or checks a PID at all — a healthy
        // 200+UP response must always be CONNECTED regardless of runtime mode.
        val probe = LocalRuntimeHealthProbe(
            apiGatewayBaseUrlProvider = { "http://127.0.0.1:8080" },
            runtimeModeProvider = { "k8s" },
            fetcher = { _: URI -> LocalRuntimeHealthProbe.HttpResult(200, """{"status":"UP"}""") },
            clock = clock
        )

        val status = probe.probe()

        assertEquals(DesktopRuntimeMonitor.ConnectionState.CONNECTED, status.state)
    }

    @Test
    fun `protected readiness response is degraded, not connected, outside k8s runtime mode`() {
        val probe = LocalRuntimeHealthProbe(
            apiGatewayBaseUrlProvider = { "http://127.0.0.1:8080" },
            runtimeModeProvider = { "local" },
            fetcher = { _: URI -> LocalRuntimeHealthProbe.HttpResult(403, "") },
            clock = clock
        )

        val status = probe.probe()

        assertEquals(DesktopRuntimeMonitor.ConnectionState.DEGRADED, status.state)
    }

    @Test
    fun `readiness probe falls back to legacy actuator health on 404`() {
        val seenUris = mutableListOf<URI>()
        val probe = LocalRuntimeHealthProbe(
            apiGatewayBaseUrlProvider = { "http://127.0.0.1:8080" },
            fetcher = { uri ->
                seenUris += uri
                when (uri.path) {
                    "/actuator/health/readiness" -> LocalRuntimeHealthProbe.HttpResult(404, "")
                    "/actuator/health" -> LocalRuntimeHealthProbe.HttpResult(200, """{"status":"UP"}""")
                    else -> error("Unexpected URI $uri")
                }
            },
            clock = clock
        )

        val status = probe.probe()

        assertEquals(DesktopRuntimeMonitor.ConnectionState.CONNECTED, status.state)
        assertEquals(
            listOf(
                URI.create("http://127.0.0.1:8080/actuator/health/readiness"),
                URI.create("http://127.0.0.1:8080/actuator/health")
            ),
            seenUris
        )
    }
}
