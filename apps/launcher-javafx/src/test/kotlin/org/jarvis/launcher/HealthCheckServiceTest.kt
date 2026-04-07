package org.jarvis.launcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthCheckServiceTest {

    @Test
    fun `optional voice failure keeps healthy runtime degraded instead of error`() {
        val checks = baselineChecks()
        val service = healthCheckService(checks)

        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.STARTING,
            service.checkHealth(backendPid = null, backendExpectedRunning = true)?.overall
        )
        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.READY,
            service.checkHealth(backendPid = null, backendExpectedRunning = true)?.overall
        )

        checks["voice-gateway"] = down("voice-gateway", "voice websocket unavailable")

        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.DEGRADED,
            service.checkHealth(backendPid = null, backendExpectedRunning = true)?.overall
        )
    }

    @Test
    fun `stable runtime only becomes error after repeated core failures`() {
        val checks = baselineChecks()
        val service = healthCheckService(checks)

        service.checkHealth(backendPid = null, backendExpectedRunning = true)
        service.checkHealth(backendPid = null, backendExpectedRunning = true)

        checks["security-service"] = down("security-service", "auth backend timed out")

        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.DEGRADED,
            service.checkHealth(backendPid = null, backendExpectedRunning = true)?.overall
        )
        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.DEGRADED,
            service.checkHealth(backendPid = null, backendExpectedRunning = true)?.overall
        )
        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.ERROR,
            service.checkHealth(backendPid = null, backendExpectedRunning = true)?.overall
        )
    }

    @Test
    fun `error recovery requires consecutive healthy samples before ready`() {
        val checks = baselineChecks()
        val service = healthCheckService(checks)

        service.checkHealth(backendPid = null, backendExpectedRunning = true)
        service.checkHealth(backendPid = null, backendExpectedRunning = true)

        checks["security-service"] = down("security-service", "auth backend timed out")
        service.checkHealth(backendPid = null, backendExpectedRunning = true)
        service.checkHealth(backendPid = null, backendExpectedRunning = true)
        service.checkHealth(backendPid = null, backendExpectedRunning = true)

        checks["security-service"] = up("security-service")

        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.STARTING,
            service.checkHealth(backendPid = null, backendExpectedRunning = true)?.overall
        )
        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.READY,
            service.checkHealth(backendPid = null, backendExpectedRunning = true)?.overall
        )
    }

    private fun healthCheckService(
        checks: MutableMap<String, HealthCheckService.ServiceHealthStatus.ServiceCheck>
    ): HealthCheckService {
        return HealthCheckService(
            apiBaseUrl = "https://api.jarvis.local",
            kubeconfigProvider = { null },
            onStatusChange = {},
            serviceCheckOverride = { name, _ -> checks[name] }
        )
    }

    private fun baselineChecks(): MutableMap<String, HealthCheckService.ServiceHealthStatus.ServiceCheck> {
        return linkedMapOf(
            "api-gateway" to up("api-gateway"),
            "security-service" to up("security-service"),
            "voice-gateway" to up("voice-gateway"),
            "smart-home-api" to up("smart-home-api"),
            "life-api" to up("life-api"),
            "analytics-api" to up("analytics-api"),
            "llm-service" to disabled("llm-service"),
            "memory-service" to disabled("memory-service")
        )
    }

    private fun up(name: String): HealthCheckService.ServiceHealthStatus.ServiceCheck {
        return HealthCheckService.ServiceHealthStatus.ServiceCheck(
            name = name,
            status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
            message = "UP"
        )
    }

    private fun down(
        name: String,
        message: String
    ): HealthCheckService.ServiceHealthStatus.ServiceCheck {
        return HealthCheckService.ServiceHealthStatus.ServiceCheck(
            name = name,
            status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
            message = message
        )
    }

    private fun disabled(name: String): HealthCheckService.ServiceHealthStatus.ServiceCheck {
        return HealthCheckService.ServiceHealthStatus.ServiceCheck(
            name = name,
            status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
            message = "Disabled in test",
            isDisabled = true
        )
    }
}
