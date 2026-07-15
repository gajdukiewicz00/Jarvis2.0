package org.jarvis.launcher

import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Third-wave HTTP coverage for [HealthCheckService]: exercises the network
 * *failure* catch branches that the UP/HTTP-code focused first/second waves
 * left uncovered — the [java.net.ConnectException] and generic [Exception]
 * handlers inside the actuator probe ([checkActuatorHealth]) and the gateway
 * surface probe ([checkGatewaySurface]).
 *
 * No live backend is contacted: a [MockWebServer] is started only to reserve a
 * loopback address, then shut down so the very next connection is refused
 * (deterministic ConnectException). The generic-error case points at an
 * out-of-range TCP port (99999), so the socket layer raises an
 * [IllegalArgumentException] entirely offline — no DNS, no network — which is
 * neither an SSL nor a ConnectException and therefore lands in the catch-all
 * handler. Only the service under test performs a real request; every other
 * service is short-circuited via [HealthCheckService.serviceCheckOverride].
 */
class HealthCheckServiceProbeErrorTest {

    private fun up(name: String) = HealthCheckService.ServiceHealthStatus.ServiceCheck(
        name = name,
        status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
        message = "UP"
    )

    private fun disabled(name: String) = HealthCheckService.ServiceHealthStatus.ServiceCheck(
        name = name,
        status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
        message = "disabled",
        isDisabled = true
    )

    private fun serviceFor(baseUrl: String, realService: String): HealthCheckService {
        return HealthCheckService(
            apiBaseUrl = baseUrl,
            kubeconfigProvider = { null },
            onStatusChange = {},
            serviceCheckOverride = { name, _ ->
                when (name) {
                    realService -> null // perform a real (failing) HTTP probe
                    "api-gateway", "security-service" -> up(name)
                    else -> disabled(name)
                }
            }
        )
    }

    /** Start then immediately stop a MockWebServer to hand back a now-closed loopback base URL. */
    private fun closedLoopbackBaseUrl(): String {
        val server = MockWebServer()
        server.start()
        val base = server.url("/").toString().removeSuffix("/")
        server.shutdown()
        return base
    }

    @Test
    fun `actuator probe against a closed port reports connection refused DOWN`() {
        val service = serviceFor(closedLoopbackBaseUrl(), realService = "api-gateway")

        val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
        val check = status?.coreServices?.get("api-gateway")

        assertNotNull(check)
        assertEquals(
            HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
            check!!.status
        )
        assertTrue(
            check.message.contains("Connection refused", ignoreCase = true),
            check.message
        )
    }

    @Test
    fun `actuator probe against an out-of-range port falls to the generic error branch`() {
        val service = serviceFor("http://127.0.0.1:99999", realService = "api-gateway")

        val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
        val check = status?.coreServices?.get("api-gateway")

        assertNotNull(check)
        assertEquals(
            HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
            check!!.status
        )
        // Neither the SSL nor the ConnectException branch — the catch-all "Error:" message.
        assertTrue(check.message.startsWith("Error"), check.message)
    }

    @Test
    fun `gateway surface probe against a closed port reports DOWN via generic error branch`() {
        val service = serviceFor(closedLoopbackBaseUrl(), realService = "smart-home-api")

        val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
        val check = status?.optionalServices?.get("smart-home-api")

        assertNotNull(check)
        assertEquals(
            HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
            check!!.status
        )
        // checkGatewaySurface has no dedicated ConnectException branch; the refusal
        // is swallowed by its catch-all and surfaced as an "Error:" message.
        assertTrue(check.message.startsWith("Error"), check.message)
    }
}
