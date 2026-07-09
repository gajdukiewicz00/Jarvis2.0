package org.jarvis.launcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the "disabled by flag" early-return branches of the optional service
 * probes (voice/llm/memory) in [HealthCheckService]. These branches return a
 * disabled [ServiceHealthStatus.ServiceCheck] before any network access, so
 * the target service is left un-overridden while every other service is
 * short-circuited via [HealthCheckService.serviceCheckOverride]. No sockets or
 * processes are touched.
 */
class HealthCheckServiceFlagDisabledTest {

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

    /** Leaves [realService] to run its own (network-free) disabled branch. */
    private fun serviceFor(realService: String): HealthCheckService {
        return HealthCheckService(
            apiBaseUrl = "https://api.jarvis.local",
            kubeconfigProvider = { null },
            onStatusChange = {},
            serviceCheckOverride = { name, _ ->
                when (name) {
                    realService -> null
                    "api-gateway", "security-service" -> up(name)
                    else -> disabled(name)
                }
            }
        )
    }

    @Test
    fun `voice gateway disabled by flag is reported disabled UNKNOWN`() {
        val service = serviceFor(realService = "voice-gateway")
        service.updateFlags(
            llmEnabled = false,
            memoryEnabled = false,
            voiceEnabled = false,
            voiceRequired = false
        )

        val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
        val check = status?.optionalServices?.get("voice-gateway")

        assertEquals(
            HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
            check?.status
        )
        assertTrue(check?.isDisabled == true, "voice-gateway should be marked intentionally disabled")
        assertTrue(check?.message?.contains("Disabled by flag") == true, check?.message)
    }

    @Test
    fun `llm service disabled by flag is reported disabled UNKNOWN`() {
        val service = serviceFor(realService = "llm-service")
        service.updateFlags(
            llmEnabled = false,
            memoryEnabled = false,
            voiceEnabled = true,
            voiceRequired = false
        )

        val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
        val check = status?.optionalServices?.get("llm-service")

        assertEquals(
            HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
            check?.status
        )
        assertTrue(check?.isDisabled == true, "llm-service should be marked intentionally disabled")
        assertTrue(check?.message?.contains("ENABLE_LLM=false") == true, check?.message)
    }

    @Test
    fun `memory service disabled by flag is reported disabled UNKNOWN`() {
        val service = serviceFor(realService = "memory-service")
        service.updateFlags(
            llmEnabled = false,
            memoryEnabled = false,
            voiceEnabled = true,
            voiceRequired = false
        )

        val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
        val check = status?.optionalServices?.get("memory-service")

        assertEquals(
            HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
            check?.status
        )
        assertTrue(check?.isDisabled == true, "memory-service should be marked intentionally disabled")
        assertTrue(check?.message?.contains("ENABLE_MEMORY=false") == true, check?.message)
    }

    @Test
    fun `disabled optional services keep runtime ready not degraded`() {
        val service = serviceFor(realService = "llm-service")
        service.updateFlags(
            llmEnabled = false,
            memoryEnabled = false,
            voiceEnabled = true,
            voiceRequired = false
        )

        // Two consecutive healthy cycles clear the READY hysteresis; disabled
        // optionals must not drag the overall state down to DEGRADED.
        service.checkHealth(backendPid = null, backendExpectedRunning = true)
        val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)

        assertEquals(
            HealthCheckService.ServiceHealthStatus.OverallStatus.READY,
            status?.overall
        )
    }
}
