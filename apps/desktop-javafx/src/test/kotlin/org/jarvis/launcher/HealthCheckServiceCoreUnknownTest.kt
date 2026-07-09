package org.jarvis.launcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the overall-status `else` branch of
 * [HealthCheckService.checkHealthInternal] (lines where the core services are
 * neither all-UP nor any-DOWN — i.e. at least one core check is a non-disabled
 * `UNKNOWN`). The existing state-machine tests only drive core services UP or
 * DOWN, so this UNKNOWN-core partition (and both of its hysteresis sub-cases,
 * STARTING when not yet stable and DEGRADED when previously stable) was
 * untouched.
 *
 * Deterministic: a [HealthCheckService.serviceCheckOverride] supplies every
 * check, so no socket or process is involved.
 */
class HealthCheckServiceCoreUnknownTest {

    private fun up(name: String) = HealthCheckService.ServiceHealthStatus.ServiceCheck(
        name = name,
        status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
        message = "UP"
    )

    /** A non-disabled UNKNOWN — e.g. the TLS-trust-missing outcome of a real probe. */
    private fun unknown(name: String) = HealthCheckService.ServiceHealthStatus.ServiceCheck(
        name = name,
        status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
        message = "TLS trust missing - click Fix TLS",
        isDisabled = false
    )

    private fun disabled(name: String) = HealthCheckService.ServiceHealthStatus.ServiceCheck(
        name = name,
        status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
        message = "disabled",
        isDisabled = true
    )

    private fun serviceWith(
        checks: Map<String, HealthCheckService.ServiceHealthStatus.ServiceCheck>
    ): HealthCheckService {
        return HealthCheckService(
            apiBaseUrl = "https://api.jarvis.local",
            kubeconfigProvider = { null },
            onStatusChange = {},
            serviceCheckOverride = { name, _ -> checks[name] ?: disabled(name) }
        )
    }

    @Test
    fun `unknown core service on a fresh runtime yields STARTING`() {
        val service = serviceWith(
            mapOf(
                "api-gateway" to unknown("api-gateway"),
                "security-service" to up("security-service")
            )
        )

        val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)

        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.STARTING,
            status?.overall
        )
        // The UNKNOWN core status is surfaced in the reasons diagnostics.
        assertTrue(
            status?.reasons?.any { it.startsWith("Core api-gateway:") && it.contains("UNKNOWN") } == true,
            status?.reasons?.toString()
        )
    }

    @Test
    fun `unknown core service after a stable READY degrades instead of restarting`() {
        val checks = linkedMapOf(
            "api-gateway" to up("api-gateway"),
            "security-service" to up("security-service")
        )
        val service = serviceWith(checks)

        // Two healthy cycles clear the READY hysteresis and set the stable flag.
        service.checkHealth(backendPid = null, backendExpectedRunning = true)
        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.READY,
            service.checkHealth(backendPid = null, backendExpectedRunning = true)?.overall
        )

        // A core service now flips to a non-disabled UNKNOWN (not UP, not DOWN).
        checks["api-gateway"] = unknown("api-gateway")

        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.DEGRADED,
            service.checkHealth(backendPid = null, backendExpectedRunning = true)?.overall
        )
    }
}
