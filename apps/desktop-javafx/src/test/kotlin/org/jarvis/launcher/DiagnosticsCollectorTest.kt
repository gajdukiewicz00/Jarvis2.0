package org.jarvis.launcher

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Covers [DiagnosticsCollector.collectDiagnostics] formatting logic.
 *
 * The Kubernetes probe branch (which spawns `kubectl`) is intentionally NOT
 * exercised: passing `backendPid = null` skips it entirely. The collector
 * writes its snapshot under `~/.jarvis/logs`; each test deletes the file it
 * produced so nothing is left behind.
 */
class DiagnosticsCollectorTest {

    private data class Result(val path: Path?, val error: String?)

    private fun collect(
        version: String,
        health: HealthCheckService.ServiceHealthStatus?,
        backendPid: Long?
    ): Result {
        JarvisPaths.ensureDirectories()
        val collector = DiagnosticsCollector()
        val latch = CountDownLatch(1)
        val pathRef = AtomicReference<Path?>(null)
        val errorRef = AtomicReference<String?>(null)
        try {
            collector.collectDiagnostics(
                launcherVersion = version,
                healthStatus = health,
                backendPid = backendPid,
                onComplete = { pathRef.set(it); latch.countDown() },
                onError = { errorRef.set(it); latch.countDown() }
            )
            assertTrue(latch.await(15, TimeUnit.SECONDS), "diagnostics collection timed out")
            return Result(pathRef.get(), errorRef.get())
        } finally {
            collector.shutdown()
        }
    }

    private fun readAndCleanup(path: Path?): String {
        assertNotNull(path)
        val content = Files.readString(path)
        Files.deleteIfExists(path)
        return content
    }

    @Test
    fun `snapshot with health status renders all sections`() {
        val health = HealthCheckService.ServiceHealthStatus(
            overall = HealthCheckService.ServiceHealthStatus.OverallStatus.READY,
            coreServices = mapOf(
                "api-gateway" to HealthCheckService.ServiceHealthStatus.ServiceCheck(
                    name = "api-gateway",
                    status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
                    message = "UP"
                )
            ),
            optionalServices = mapOf(
                "llm-service" to HealthCheckService.ServiceHealthStatus.ServiceCheck(
                    name = "llm-service",
                    status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
                    message = "Disabled by flag",
                    isDisabled = true
                )
            ),
            reasons = listOf("Bootstrap process: alive")
        )

        val result = collect("9.9.9-test", health, backendPid = null)
        assertNull(result.error)
        val content = readAndCleanup(result.path)

        assertTrue(content.contains("Jarvis 2.0 Diagnostics Snapshot"), content)
        assertTrue(content.contains("Version: 9.9.9-test"))
        assertTrue(content.contains("Bootstrap PID: Not running"))
        assertTrue(content.contains("--- System ---"))
        assertTrue(content.contains("--- Last Run Summary ---"))
        assertTrue(content.contains("--- Health Status ---"))
        assertTrue(content.contains("Overall: READY"))
        assertTrue(content.contains("api-gateway"))
        assertTrue(content.contains("Reasons:"))
        assertTrue(content.contains("--- Lock Files ---"))
    }

    @Test
    fun `snapshot without health status omits the health section`() {
        val result = collect("0.0.0", health = null, backendPid = null)
        assertNull(result.error)
        val content = readAndCleanup(result.path)

        assertTrue(content.contains("Jarvis 2.0 Diagnostics Snapshot"))
        assertTrue(content.contains("Version: 0.0.0"))
        assertTrue(content.contains("Bootstrap PID: Not running"))
        assertFalse(content.contains("--- Health Status ---"))
        // Kubernetes section only appears when a backend PID is supplied.
        assertFalse(content.contains("--- Kubernetes Status ---"))
    }
}
