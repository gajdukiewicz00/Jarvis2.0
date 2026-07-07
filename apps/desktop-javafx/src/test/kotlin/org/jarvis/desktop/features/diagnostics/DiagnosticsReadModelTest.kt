package org.jarvis.desktop.features.diagnostics

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.features.status.StatusLevel
import org.jarvis.desktop.service.DesktopServiceHealthChecker
import org.jarvis.launcher.HealthCheckService
import org.jarvis.launcher.LauncherSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * [DiagnosticsReadModel] reads a handful of real host paths under `~/.jarvis`
 * (launcher.log, backend-launch.log, backend.pid) that this test does not —
 * and must not — control or overwrite (see [org.jarvis.launcher.JarvisPaths]:
 * those paths are hard-coded, not dependency-injected). Those reads are
 * side-effect-free, so exercising [DiagnosticsReadModel.refresh] end-to-end is
 * safe, but this test intentionally avoids asserting on the exact health
 * status enum (it depends on whatever is/isn't running on this host) and
 * instead pins down the structural contract that holds on every machine.
 */
class DiagnosticsReadModelTest {

    private fun configFor(server: MockWebServer): () -> ResolvedDesktopConfig {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = baseUrl,
                apiBaseUrl = "$baseUrl/api/v1",
                voiceWebSocketUrl = "$baseUrl/ws/voice",
                pcControlWebSocketUrl = "$baseUrl/ws/pc-control",
                locale = Locale.ENGLISH,
                voiceLanguage = "en-US",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                apiGatewayReason = "test",
                usesManualEndpointOverride = true
            )
        }
    }

    @Test
    fun `refresh returns a structurally complete snapshot without touching real runtime config`() {
        // Server backing the endpoint checker (org.jarvis.desktop.service.DesktopServiceHealthChecker):
        // gateway health + 4 REST checks + 2 websocket upgrade attempts = 7 requests, all answered here.
        val endpointServer = MockWebServer()
        repeat(7) { endpointServer.enqueue(MockResponse().setBody("""{"status":"UP"}""")) }

        // A second server we start-then-shutdown so the *internal* runtime health
        // checker (built by DiagnosticsReadModel itself, not injectable) gets a
        // deterministic, fast connection-refused rather than depending on
        // whatever may or may not be listening on this host's real ports.
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString().removeSuffix("/")
        deadServer.shutdown()

        try {
            endpointServer.start()
            val apiClient = ApiClient(configFor(endpointServer))

            val model = DiagnosticsReadModel(
                apiClient = apiClient,
                configProvider = configFor(endpointServer),
                launcherSettingsProvider = {
                    LauncherSettings(enableLlm = false, enableMemory = false, aiAutoStart = false)
                },
                runtimeApiBaseUrlProvider = { deadUrl },
                kubeconfigProvider = { null }
            )

            val snapshot = model.refresh()

            assertNotNull(snapshot.refreshedAt)
            assertEquals(deadUrl, snapshot.runtime.apiBaseUrl)
            assertNotNull(snapshot.runtime.status)
            assertTrue(snapshot.runtime.runtimeMode.isNotBlank())

            assertEquals(7, snapshot.endpoints.checks.size)
            assertEquals(endpointServer.url("/").toString().removeSuffix("/") + "/api/v1", snapshot.endpoints.config.apiBaseUrl)

            assertEquals(2, snapshot.logPreviews.size)
            assertEquals("launcher.log", snapshot.logPreviews[0].label)
            assertEquals("backend-launch.log", snapshot.logPreviews[1].label)
        } finally {
            endpointServer.shutdown()
        }
    }

    @Test
    fun `OverallStatus toStatusLevel maps every value onto the canonical vocabulary`() {
        assertEquals(StatusLevel.UP, HealthCheckService.ServiceHealthStatus.OverallStatus.READY.toStatusLevel())
        assertEquals(StatusLevel.DEGRADED, HealthCheckService.ServiceHealthStatus.OverallStatus.DEGRADED.toStatusLevel())
        assertEquals(StatusLevel.UNKNOWN, HealthCheckService.ServiceHealthStatus.OverallStatus.STARTING.toStatusLevel())
        assertEquals(StatusLevel.DOWN, HealthCheckService.ServiceHealthStatus.OverallStatus.ERROR.toStatusLevel())
        assertEquals(StatusLevel.UNKNOWN, HealthCheckService.ServiceHealthStatus.OverallStatus.IDLE.toStatusLevel())
    }

    @Test
    fun `ServiceCheck toStatusLevel treats an intentionally-disabled UNKNOWN check as healthy`() {
        val disabled = HealthCheckService.ServiceHealthStatus.ServiceCheck(
            name = "llm-service",
            status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
            message = "Disabled by flag",
            isDisabled = true
        )

        val level = disabled.toStatusLevel()
        assertEquals(StatusLevel.DISABLED, level)
        assertTrue(level.isHealthy)
    }

    @Test
    fun `ServiceCheck toStatusLevel treats a non-disabled UNKNOWN check as a real, unhealthy signal`() {
        val errored = HealthCheckService.ServiceHealthStatus.ServiceCheck(
            name = "voice-gateway",
            status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
            message = "kubectl unavailable: timeout",
            isDisabled = false
        )

        val level = errored.toStatusLevel()
        assertEquals(StatusLevel.DEGRADED, level)
        assertFalse(level.isHealthy)
    }

    @Test
    fun `ServiceCheck toStatusLevel maps UP and DOWN directly`() {
        val up = HealthCheckService.ServiceHealthStatus.ServiceCheck(
            name = "api-gateway",
            status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
            message = "UP"
        )
        val down = HealthCheckService.ServiceHealthStatus.ServiceCheck(
            name = "api-gateway",
            status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
            message = "Connection refused"
        )

        assertEquals(StatusLevel.UP, up.toStatusLevel())
        assertEquals(StatusLevel.DOWN, down.toStatusLevel())
    }

    @Test
    fun `DesktopServiceHealthChecker Status toStatusLevel treats UNAUTHORIZED as PROTECTED (healthy)`() {
        // This is the concrete cross-screen reconciliation: Service Status already
        // treats HTTP 401/403 as healthy (PROTECTED). Diagnostics' endpoint probes
        // must reach the exact same conclusion for the exact same HTTP signal.
        assertEquals(StatusLevel.UP, DesktopServiceHealthChecker.Status.ONLINE.toStatusLevel())
        val protectedLevel = DesktopServiceHealthChecker.Status.UNAUTHORIZED.toStatusLevel()
        assertEquals(StatusLevel.PROTECTED, protectedLevel)
        assertTrue(protectedLevel.isHealthy)
        assertEquals(StatusLevel.DOWN, DesktopServiceHealthChecker.Status.OFFLINE.toStatusLevel())
    }
}
