package org.jarvis.launcher

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Collects diagnostic information about Jarvis installation.
 * Creates a snapshot file with system info, logs, and Kubernetes status.
 */
class DiagnosticsCollector {
    private val logger = LoggerFactory.getLogger(DiagnosticsCollector::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    
    /**
     * Collect diagnostics and save to file.
     * Returns path to the created file.
     */
    fun collectDiagnostics(
        launcherVersion: String,
        healthStatus: HealthCheckService.ServiceHealthStatus?,
        backendPid: Long?,
        onComplete: (Path) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                val diagnosticsFile = JarvisPaths.logs.resolve("diagnostics-$timestamp.txt")
                
                val diagnostics = StringBuilder()
                
                // Header
                diagnostics.appendLine("=".repeat(60))
                diagnostics.appendLine("Jarvis 2.0 Diagnostics Snapshot")
                diagnostics.appendLine("=".repeat(60))
                diagnostics.appendLine("Timestamp: ${LocalDateTime.now()}")
                diagnostics.appendLine("")
                
                // Launcher version
                diagnostics.appendLine("--- Launcher ---")
                diagnostics.appendLine("Version: $launcherVersion")
                diagnostics.appendLine("Runtime target: ${JarvisPaths.describeRuntimeTarget()}")
                diagnostics.appendLine("")
                
                // OS + Java
                diagnostics.appendLine("--- System ---")
                diagnostics.appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                diagnostics.appendLine("Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
                diagnostics.appendLine("User: ${System.getProperty("user.name")}")
                diagnostics.appendLine("Home: ${System.getProperty("user.home")}")
                diagnostics.appendLine("")
                
                // Backend status
                diagnostics.appendLine("--- Backend Bootstrap ---")
                if (backendPid != null) {
                    val isAlive = try {
                        java.lang.ProcessHandle.of(backendPid)
                            .map { it.isAlive }
                            .orElse(false)
                    } catch (e: Exception) {
                        false
                    }
                    diagnostics.appendLine("Bootstrap PID: $backendPid (${if (isAlive) "RUNNING" else "STOPPED"})")
                } else {
                    diagnostics.appendLine("Bootstrap PID: Not running")
                }
                diagnostics.appendLine("Note: local runtime services may remain healthy after the bootstrap shell exits.")
                diagnostics.appendLine("")

                diagnostics.appendLine("--- Last Run Summary ---")
                val lastRunSummary = JarvisPaths.loadRuntimeRunSummary()
                if (lastRunSummary != null) {
                    diagnostics.appendLine("status: ${lastRunSummary.status ?: "unknown"}")
                    diagnostics.appendLine("runtimeMode: ${lastRunSummary.runtimeMode ?: "unknown"}")
                    diagnostics.appendLine("apiUrl: ${lastRunSummary.apiUrl ?: "n/a"}")
                    diagnostics.appendLine("voiceUrl: ${lastRunSummary.voiceUrl ?: "n/a"}")
                    diagnostics.appendLine("grafanaUrl: ${lastRunSummary.grafanaUrl ?: "n/a"}")
                    diagnostics.appendLine("timestamp: ${lastRunSummary.timestamp ?: "n/a"}")
                } else {
                    diagnostics.appendLine("missing")
                }
                diagnostics.appendLine("")

                diagnostics.appendLine("--- Observability Verification ---")
                try {
                    val observabilitySummary = JarvisPaths.observabilityStatus
                    if (Files.exists(observabilitySummary)) {
                        diagnostics.appendLine(Files.readString(observabilitySummary).trim())
                    } else {
                        diagnostics.appendLine("missing")
                    }
                } catch (e: Exception) {
                    diagnostics.appendLine("Error reading observability summary: ${e.message}")
                }
                diagnostics.appendLine("")
                
                // Health status
                if (healthStatus != null) {
                    diagnostics.appendLine("--- Health Status ---")
                    diagnostics.appendLine("Overall: ${healthStatus.overall}")
                    diagnostics.appendLine("")
                    diagnostics.appendLine("Core Services:")
                    healthStatus.coreServices.forEach { (name, check) ->
                        diagnostics.appendLine("  $name: ${check.status} - ${check.message}")
                    }
                    diagnostics.appendLine("")
                    diagnostics.appendLine("Optional Services:")
                    healthStatus.optionalServices.forEach { (name, check) ->
                        diagnostics.appendLine("  $name: ${check.status} - ${check.message}")
                    }
                    diagnostics.appendLine("")
                    diagnostics.appendLine("Reasons:")
                    healthStatus.reasons.forEach { reason ->
                        diagnostics.appendLine("  - $reason")
                    }
                    diagnostics.appendLine("")
                }
                
                // Locks
                diagnostics.appendLine("--- Lock Files ---")
                val launcherLock = JarvisPaths.run.resolve("launcher.lock")
                val backendLock = JarvisPaths.run.resolve("backend.lock")
                diagnostics.appendLine("launcher.lock: ${if (Files.exists(launcherLock)) "EXISTS" else "NOT FOUND"}")
                diagnostics.appendLine("backend.lock: ${if (Files.exists(backendLock)) "EXISTS" else "NOT FOUND"}")
                diagnostics.appendLine("")
                
                // Stage 6: Get secret keys for masking
                val secretKeys = SecurityUtils.getCommonSecretKeys()
                
                // Last 100 lines of logs (masked)
                diagnostics.appendLine("--- Last 100 lines: launcher.log (secrets masked) ---")
                try {
                    val launcherLog = JarvisPaths.launcherLog
                    if (Files.exists(launcherLog)) {
                        val lines = Files.readAllLines(launcherLog)
                        val startIndex = maxOf(0, lines.size - 100)
                        lines.subList(startIndex, lines.size).forEach { line ->
                            diagnostics.appendLine(SecurityUtils.maskSensitiveData(line, secretKeys))
                        }
                    } else {
                        diagnostics.appendLine("File not found")
                    }
                } catch (e: Exception) {
                    diagnostics.appendLine("Error reading: ${e.message}")
                }
                diagnostics.appendLine("")
                
                diagnostics.appendLine("--- Last 100 lines: backend-launch.log (secrets masked) ---")
                try {
                    val backendLog = JarvisPaths.backendLaunchLog
                    if (Files.exists(backendLog)) {
                        val lines = Files.readAllLines(backendLog)
                        val startIndex = maxOf(0, lines.size - 100)
                        lines.subList(startIndex, lines.size).forEach { line ->
                            diagnostics.appendLine(SecurityUtils.maskSensitiveData(line, secretKeys))
                        }
                    } else {
                        diagnostics.appendLine("File not found")
                    }
                } catch (e: Exception) {
                    diagnostics.appendLine("Error reading: ${e.message}")
                }
                diagnostics.appendLine("")

                diagnostics.appendLine("--- Last 100 lines: desktop.log (secrets masked) ---")
                try {
                    val desktopLog = JarvisPaths.desktopLog
                    if (Files.exists(desktopLog)) {
                        val lines = Files.readAllLines(desktopLog)
                        val startIndex = maxOf(0, lines.size - 100)
                        lines.subList(startIndex, lines.size).forEach { line ->
                            diagnostics.appendLine(SecurityUtils.maskSensitiveData(line, secretKeys))
                        }
                    } else {
                        diagnostics.appendLine("File not found")
                    }
                } catch (e: Exception) {
                    diagnostics.appendLine("Error reading: ${e.message}")
                }
                diagnostics.appendLine("")
                
                // Kubernetes summary (if backend running)
                if (backendPid != null) {
                    diagnostics.appendLine("--- Kubernetes Status ---")
                    try {
                        val processBuilder = ProcessBuilder("kubectl", "get", "pods", "-n", "jarvis")
                        processBuilder.redirectErrorStream(true)
                        val process = processBuilder.start()
                        val output = process.inputStream.bufferedReader().readText()
                        
                        // Wait with timeout
                        val finished = process.waitFor(5, TimeUnit.SECONDS)
                        if (finished) {
                            diagnostics.appendLine("Pods:")
                            // Stage 6: Mask secrets in kubectl output
                            diagnostics.appendLine(SecurityUtils.maskSensitiveData(output, secretKeys))
                        } else {
                            diagnostics.appendLine("kubectl timeout (5s)")
                        }
                    } catch (e: Exception) {
                        diagnostics.appendLine("kubectl not available: ${e.message}")
                    }
                    diagnostics.appendLine("")
                    
                    try {
                        val processBuilder = ProcessBuilder("kubectl", "get", "deploy", "-n", "jarvis")
                        processBuilder.redirectErrorStream(true)
                        val process = processBuilder.start()
                        val output = process.inputStream.bufferedReader().readText()
                        
                        val finished = process.waitFor(5, TimeUnit.SECONDS)
                        if (finished) {
                            diagnostics.appendLine("Deployments:")
                            // Stage 6: Mask secrets in kubectl output
                            diagnostics.appendLine(SecurityUtils.maskSensitiveData(output, secretKeys))
                        }
                    } catch (e: Exception) {
                        diagnostics.appendLine("kubectl deploy check failed: ${e.message}")
                    }
                    diagnostics.appendLine("")
                }
                
                // Write to file
                Files.writeString(diagnosticsFile, diagnostics.toString())
                
                logger.info("Diagnostics saved to: $diagnosticsFile")
                onComplete(diagnosticsFile)
            } catch (e: Exception) {
                logger.error("Failed to collect diagnostics", e)
                onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Shutdown the executor service.
     */
    fun shutdown() {
        executor.shutdown()
    }
}
