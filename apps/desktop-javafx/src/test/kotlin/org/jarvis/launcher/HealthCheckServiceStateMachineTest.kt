package org.jarvis.launcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [HealthCheckService]'s status state-machine (IDLE detection,
 * required-vs-optional partitioning, hysteresis, reasons, callbacks) using
 * a deterministic [HealthCheckService.serviceCheckOverride] so no network
 * or process is involved.
 */
class HealthCheckServiceStateMachineTest {

    private fun up(name: String) = HealthCheckService.ServiceHealthStatus.ServiceCheck(
        name = name,
        status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
        message = "UP"
    )

    private fun down(name: String) = HealthCheckService.ServiceHealthStatus.ServiceCheck(
        name = name,
        status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
        message = "down"
    )

    private fun disabled(name: String) = HealthCheckService.ServiceHealthStatus.ServiceCheck(
        name = name,
        status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
        message = "disabled",
        isDisabled = true
    )

    private fun serviceWith(
        checks: Map<String, HealthCheckService.ServiceHealthStatus.ServiceCheck>,
        onStatusChange: (HealthCheckService.ServiceHealthStatus) -> Unit = {}
    ): HealthCheckService {
        return HealthCheckService(
            apiBaseUrl = "https://api.jarvis.local",
            kubeconfigProvider = { null },
            onStatusChange = onStatusChange,
            serviceCheckOverride = { name, _ -> checks[name] ?: disabled(name) }
        )
    }

    @Test
    fun `initial status before any check is idle`() {
        val service = serviceWith(emptyMap())
        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.IDLE,
            service.getCurrentStatus()?.overall
        )
    }

    @Test
    fun `nothing reachable and no bootstrap yields idle with reason`() {
        val service = serviceWith(mapOf("api-gateway" to down("api-gateway")))
        val status = service.checkHealth(backendPid = null, backendExpectedRunning = false)
        assertEquals(HealthCheckService.ServiceHealthStatus.OverallStatus.IDLE, status?.overall)
        assertTrue(status?.coreServices?.isEmpty() == true)
        assertTrue(status?.reasons?.any { it.contains("Backend not running") } == true)
    }

    @Test
    fun `status change callback fires on transition out of idle`() {
        val transitions = mutableListOf<HealthCheckService.ServiceHealthStatus.OverallStatus>()
        val service = serviceWith(
            checks = mapOf(
                "api-gateway" to up("api-gateway"),
                "security-service" to up("security-service")
            ),
            onStatusChange = { transitions.add(it.overall) }
        )

        service.checkHealth(backendPid = null, backendExpectedRunning = true)

        assertTrue(transitions.isNotEmpty())
        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.STARTING,
            transitions.first()
        )
    }

    @Test
    fun `reasons include core service status lines`() {
        val service = serviceWith(
            mapOf(
                "api-gateway" to up("api-gateway"),
                "security-service" to up("security-service")
            )
        )
        val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
        assertTrue(status?.reasons?.any { it.startsWith("Bootstrap process:") } == true)
        assertTrue(status?.reasons?.any { it.startsWith("Core api-gateway:") } == true)
    }

    @Test
    fun `voice required promotes voice gateway to a core dependency`() {
        val service = serviceWith(
            mapOf(
                "api-gateway" to up("api-gateway"),
                "security-service" to up("security-service"),
                "voice-gateway" to down("voice-gateway")
            )
        )
        service.updateFlags(
            llmEnabled = false,
            memoryEnabled = false,
            voiceEnabled = true,
            voiceRequired = true
        )

        // First two checks stay in STARTING under failure hysteresis...
        service.checkHealth(backendPid = null, backendExpectedRunning = true)
        service.checkHealth(backendPid = null, backendExpectedRunning = true)
        // ...third consecutive core failure escalates to ERROR.
        val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)

        assertEquals(HealthCheckService.ServiceHealthStatus.OverallStatus.ERROR, status?.overall)
        assertTrue(status?.coreServices?.containsKey("voice-gateway") == true)
    }

    @Test
    fun `all core up with healthy optionals reaches ready after hysteresis`() {
        val service = serviceWith(
            mapOf(
                "api-gateway" to up("api-gateway"),
                "security-service" to up("security-service"),
                "voice-gateway" to up("voice-gateway"),
                "smart-home-api" to up("smart-home-api"),
                "life-api" to up("life-api"),
                "analytics-api" to up("analytics-api"),
                "llm-service" to disabled("llm-service"),
                "memory-service" to disabled("memory-service")
            )
        )

        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.STARTING,
            service.checkHealth(backendPid = null, backendExpectedRunning = true)?.overall
        )
        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.READY,
            service.checkHealth(backendPid = null, backendExpectedRunning = true)?.overall
        )
        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.READY,
            service.getCurrentStatus()?.overall
        )
    }
}
