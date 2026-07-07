package org.jarvis.desktop.features.diagnostics

import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.features.status.StatusLevel
import org.jarvis.desktop.service.DesktopServiceHealthChecker
import org.jarvis.launcher.HealthCheckService
import org.jarvis.launcher.JarvisPaths
import org.jarvis.launcher.LauncherConfig
import org.jarvis.launcher.LauncherSettings
import org.jarvis.launcher.SecurityUtils
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class DiagnosticsReadModel(
    apiClient: ApiClient,
    private val configProvider: () -> ResolvedDesktopConfig = AppConfig::current,
    private val launcherSettingsProvider: () -> LauncherSettings = { LauncherConfig(JarvisPaths.launcherConfig).load() },
    private val runtimeApiBaseUrlProvider: () -> String = { JarvisPaths.getApiGatewayUrl() },
    private val kubeconfigProvider: () -> String? = { System.getenv("KUBECONFIG") }
) {
    data class Snapshot(
        val refreshedAt: Instant,
        val runtime: RuntimeSnapshot,
        val endpoints: EndpointSnapshot,
        val logPreviews: List<LogPreview>
    )

    data class RuntimeSnapshot(
        val apiBaseUrl: String,
        val runtimeMode: String,
        val backendPid: Long?,
        val backendProcessAlive: Boolean,
        val llmEnabled: Boolean,
        val memoryEnabled: Boolean,
        val voiceRequired: Boolean,
        val status: HealthCheckService.ServiceHealthStatus
    )

    data class EndpointSnapshot(
        val config: ResolvedDesktopConfig,
        val checks: List<DesktopServiceHealthChecker.ServiceCheck>
    )

    data class LogPreview(
        val label: String,
        val path: Path,
        val exists: Boolean,
        val text: String
    )

    private val endpointChecker = DesktopServiceHealthChecker(
        apiClient = apiClient,
        configProvider = configProvider
    )
    private val runtimeHealthChecker = HealthCheckService(
        apiBaseUrl = runtimeApiBaseUrlProvider(),
        kubeconfigProvider = kubeconfigProvider,
        onStatusChange = {}
    )

    fun refresh(): Snapshot {
        val runtimeFlags = resolveRuntimeFlags()
        runtimeHealthChecker.updateFlags(
            llmEnabled = runtimeFlags.llmEnabled,
            memoryEnabled = runtimeFlags.memoryEnabled,
            voiceRequired = runtimeFlags.voiceRequired
        )

        val backendPid = readBackendPid()
        val runtimeStatus = runtimeHealthChecker.checkHealth(
            backendPid = backendPid,
            backendExpectedRunning = backendPid != null
        ) ?: runtimeHealthChecker.getCurrentStatus() ?: unavailableRuntimeStatus()

        return Snapshot(
            refreshedAt = Instant.now(),
            runtime = RuntimeSnapshot(
                apiBaseUrl = runtimeApiBaseUrlProvider(),
                runtimeMode = JarvisPaths.getRuntimeMode(),
                backendPid = backendPid,
                backendProcessAlive = backendPid?.let(::isProcessAlive) ?: false,
                llmEnabled = runtimeFlags.llmEnabled,
                memoryEnabled = runtimeFlags.memoryEnabled,
                voiceRequired = runtimeFlags.voiceRequired,
                status = runtimeStatus
            ),
            endpoints = EndpointSnapshot(
                config = endpointChecker.resolvedConfig(),
                checks = endpointChecker.checkAll()
            ),
            logPreviews = listOf(
                readLogPreview("launcher.log", JarvisPaths.launcherLog),
                readLogPreview("backend-launch.log", JarvisPaths.backendLaunchLog)
            )
        )
    }

    private fun resolveRuntimeFlags(): RuntimeFlags {
        val persisted = try {
            launcherSettingsProvider()
        } catch (_: Exception) {
            LauncherSettings()
        }

        return RuntimeFlags(
            llmEnabled = System.getenv("JARVIS_ENABLE_LLM")?.toBoolean() ?: persisted.enableLlm,
            memoryEnabled = System.getenv("JARVIS_ENABLE_MEMORY")?.toBoolean() ?: persisted.enableMemory,
            voiceRequired = System.getenv("JARVIS_REQUIRE_VOICE_GATEWAY")?.toBoolean() ?: false
        )
    }

    private fun readBackendPid(): Long? {
        return try {
            if (Files.exists(JarvisPaths.backendPid)) {
                Files.readString(JarvisPaths.backendPid).trim().toLongOrNull()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isProcessAlive(pid: Long): Boolean {
        return try {
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        } catch (_: Exception) {
            false
        }
    }

    private fun readLogPreview(
        label: String,
        path: Path,
        maxLines: Int = 24
    ): LogPreview {
        if (!Files.isRegularFile(path)) {
            return LogPreview(
                label = label,
                path = path,
                exists = false,
                text = "File not found: ${path.toAbsolutePath()}"
            )
        }

        return try {
            val buffer = ArrayDeque<String>()
            val secretKeys = SecurityUtils.getCommonSecretKeys()

            Files.newBufferedReader(path).useLines { lines ->
                lines.forEach { line ->
                    if (buffer.size == maxLines) {
                        buffer.removeFirst()
                    }
                    buffer.addLast(SecurityUtils.maskSensitiveData(line, secretKeys))
                }
            }

            LogPreview(
                label = label,
                path = path,
                exists = true,
                text = if (buffer.isEmpty()) "(empty)" else buffer.joinToString("\n")
            )
        } catch (e: Exception) {
            LogPreview(
                label = label,
                path = path,
                exists = false,
                text = "Error reading ${path.fileName}: ${e.message ?: "Unknown error"}"
            )
        }
    }

    private fun unavailableRuntimeStatus(): HealthCheckService.ServiceHealthStatus {
        return HealthCheckService.ServiceHealthStatus(
            overall = HealthCheckService.ServiceHealthStatus.OverallStatus.IDLE,
            coreServices = emptyMap(),
            optionalServices = emptyMap(),
            reasons = listOf("Runtime health is currently unavailable")
        )
    }

    private data class RuntimeFlags(
        val llmEnabled: Boolean,
        val memoryEnabled: Boolean,
        val voiceRequired: Boolean
    )
}

/**
 * Canonical mapping from the launcher-side [HealthCheckService.ServiceHealthStatus.OverallStatus]
 * onto the shared [StatusLevel] vocabulary.
 *
 * [HealthCheckService.ServiceHealthStatus.OverallStatus.STARTING] and
 * [HealthCheckService.ServiceHealthStatus.OverallStatus.IDLE] both map to
 * [StatusLevel.UNKNOWN] rather than a bespoke "in progress" tone — until the
 * launcher-side health model firmly resolves to READY/DEGRADED/ERROR, this
 * screen says "unknown" instead of implying a specific outcome.
 */
fun HealthCheckService.ServiceHealthStatus.OverallStatus.toStatusLevel(): StatusLevel = when (this) {
    HealthCheckService.ServiceHealthStatus.OverallStatus.READY -> StatusLevel.UP
    HealthCheckService.ServiceHealthStatus.OverallStatus.DEGRADED -> StatusLevel.DEGRADED
    HealthCheckService.ServiceHealthStatus.OverallStatus.STARTING -> StatusLevel.UNKNOWN
    HealthCheckService.ServiceHealthStatus.OverallStatus.ERROR -> StatusLevel.DOWN
    HealthCheckService.ServiceHealthStatus.OverallStatus.IDLE -> StatusLevel.UNKNOWN
}

/**
 * Canonical mapping for an individual launcher-side [HealthCheckService.ServiceHealthStatus.ServiceCheck].
 *
 * An [HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN] check that
 * is intentionally disabled (a flag turned it off) is healthy — [StatusLevel.DISABLED].
 * The same raw status when NOT disabled means the check itself failed to resolve
 * (e.g. kubectl unavailable) — that is a real signal worth surfacing, so it maps
 * to [StatusLevel.DEGRADED] rather than the quieter [StatusLevel.UNKNOWN].
 */
fun HealthCheckService.ServiceHealthStatus.ServiceCheck.toStatusLevel(): StatusLevel = when (status) {
    HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP -> StatusLevel.UP
    HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN -> StatusLevel.DOWN
    HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN ->
        if (isDisabled) StatusLevel.DISABLED else StatusLevel.DEGRADED
}

/**
 * Canonical mapping from the desktop/client-facing [DesktopServiceHealthChecker.Status]
 * onto the shared [StatusLevel] vocabulary.
 *
 * [DesktopServiceHealthChecker.Status.UNAUTHORIZED] (HTTP 401/403) means the
 * endpoint answered — it is reachable, just gated behind auth — so it maps to
 * [StatusLevel.PROTECTED] (healthy), the same conclusion
 * [org.jarvis.desktop.features.status.ServiceStatusReadModel] already reaches for the
 * identical HTTP signal. Reporting this as a warning here while Service Status
 * reports it as healthy is exactly the kind of cross-screen contradiction this
 * mapping exists to close.
 */
fun DesktopServiceHealthChecker.Status.toStatusLevel(): StatusLevel = when (this) {
    DesktopServiceHealthChecker.Status.ONLINE -> StatusLevel.UP
    DesktopServiceHealthChecker.Status.UNAUTHORIZED -> StatusLevel.PROTECTED
    DesktopServiceHealthChecker.Status.OFFLINE -> StatusLevel.DOWN
}
