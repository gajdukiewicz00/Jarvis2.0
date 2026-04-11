package org.jarvis.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime

/**
 * Centralized paths for Jarvis product data.
 * All paths are under ~/.jarvis/
 */
object JarvisPaths {
    private const val LOCAL_RUNTIME_MODE = "local"
    private const val K8S_RUNTIME_MODE = "k8s"
    private const val DEFAULT_LOCAL_API_GATEWAY = "http://127.0.0.1:8080"
    private const val DEFAULT_REMOTE_API_GATEWAY = "https://api.jarvis.local"
    private const val DEFAULT_REMOTE_GRAFANA = "https://grafana.jarvis.local"
    private val userHome: String = System.getProperty("user.home")
    private val jarvisRoot: Path = Paths.get(userHome, ".jarvis")
    
    val root: Path = jarvisRoot
    val logs: Path = jarvisRoot.resolve("logs")
    val run: Path = jarvisRoot.resolve("run")
    val data: Path = jarvisRoot.resolve("data")
    val config: Path = jarvisRoot.resolve("config")
    
    // Log files
    val launcherLog: Path = logs.resolve("launcher.log")
    val backendLaunchLog: Path = logs.resolve("backend-launch.log")
    val desktopLog: Path = logs.resolve("desktop.log")
    val installLog: Path = logs.resolve("install.log")  // Stage 12

    // Config files
    val launcherConfig: Path = config.resolve("launcher.properties")
    
    // PID/lock files
    val backendPid: Path = run.resolve("backend.pid")
    val launcherPid: Path = run.resolve("launcher.pid")
    val desktopPid: Path = run.resolve("desktop.pid")
    val lastRunSummary: Path = run.resolve("last-run.json")
    val observabilityStatus: Path = run.resolve("observability-status.json")
    
    // Stage 12: Release source tracking
    val releaseSourceFile: Path = jarvisRoot.resolve("app").resolve("RELEASE_SOURCE")

    data class RuntimeRunSummary(
        val timestamp: String?,
        val status: String?,
        val apiUrl: String?,
        val voiceUrl: String?,
        val runtimeMode: String?,
        val grafanaUrl: String?
    )
    
    /**
     * Ensure all required directories exist.
     * Called on launcher startup.
     */
    fun ensureDirectories() {
        listOf(logs, run, data, config).forEach { dir ->
            if (!Files.exists(dir)) {
                Files.createDirectories(dir)
            }
        }
    }
    
    /**
     * Get project root directory (where jarvis-launch.sh is located).
     */
    fun getProjectRoot(): Path {
        fun isValidProjectRoot(path: Path): Boolean {
            return Files.exists(path.resolve("jarvis-launch.sh")) &&
                Files.exists(path.resolve("pom.xml")) &&
                Files.isDirectory(path.resolve("apps"))
        }

        fun releaseSourceRoot(): Path? {
            if (!Files.isRegularFile(releaseSourceFile)) {
                return null
            }
            val raw = runCatching { Files.readString(releaseSourceFile) }.getOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() && it != "REPO" }
                ?: return null
            val candidate = runCatching { Paths.get(raw) }.getOrNull() ?: return null
            return candidate.takeIf { isValidProjectRoot(it) }
        }

        // Try environment variable first (set by launcher or user)
        val envRoot = System.getenv("JARVIS_PROJECT_ROOT")
        if (envRoot != null) {
            val root = Paths.get(envRoot)
            if (isValidProjectRoot(root)) {
                return root
            }
        }

        releaseSourceRoot()?.let { return it }
        
        // Try to find project root by looking for jarvis-launch.sh
        val currentDir = Paths.get(System.getProperty("user.dir"))
        if (isValidProjectRoot(currentDir)) {
            return currentDir
        }
        
        // Fallback: assume we're in apps/launcher-javafx, go up 2 levels
        val assumedRoot = currentDir.parent?.parent
        if (assumedRoot != null && isValidProjectRoot(assumedRoot)) {
            return assumedRoot
        }
        
        // Last resort: try common locations
        val commonPaths = listOf(
            Paths.get(System.getProperty("user.home"), "Jarvis", "Jarvis2.0"),
            Paths.get(System.getProperty("user.home"), "IdeaProjects", "Jarvis2.0"),
            Paths.get(System.getProperty("user.home"), "Projects", "Jarvis2.0"),
            Paths.get("/opt/jarvis"),
            Paths.get("/usr/local/jarvis")
        )
        
        for (path in commonPaths) {
            if (isValidProjectRoot(path)) {
                return path
            }
        }
        
        // If nothing found, return current directory (will fail later with clear error)
        return currentDir
    }
    
    /**
     * Get path to jarvis-launch.sh script.
     */
    fun getLaunchScript(): Path {
        if (isLocalRuntime()) {
            val localScript = getProjectRoot().resolve("scripts/runtime-up.sh")
            if (Files.exists(localScript)) {
                return localScript
            }
        }
        return getProjectRoot().resolve("jarvis-launch.sh")
    }
    
    /**
     * Get path to jarvis-stop.sh script.
     */
    fun getStopScript(): Path {
        if (isLocalRuntime()) {
            val localScript = getProjectRoot().resolve("scripts/runtime-down.sh")
            if (Files.exists(localScript)) {
                return localScript
            }
        }
        return getProjectRoot().resolve("jarvis-stop.sh")
    }

    fun isLocalRuntime(): Boolean {
        return getRuntimeMode() == LOCAL_RUNTIME_MODE
    }

    fun getRuntimeMode(): String {
        val explicitMode = normalizeRuntimeMode(System.getenv("JARVIS_RUNTIME_MODE"))
        if (explicitMode != null) {
            return explicitMode
        }

        val explicitUrl = System.getenv("JARVIS_API_BASE_URL")
            ?: System.getenv("API_URL")
        if (!explicitUrl.isNullOrBlank()) {
            return if (isLocalUrl(explicitUrl)) LOCAL_RUNTIME_MODE else K8S_RUNTIME_MODE
        }

        val summary = loadRuntimeRunSummary()
        val summaryMode = normalizeRuntimeMode(summary?.runtimeMode)
        val summaryStatus = summary?.status
        if (summaryMode != null && !summaryStatus.equals("stopped", ignoreCase = true)) {
            return summaryMode
        }

        return K8S_RUNTIME_MODE
    }
    
    /**
     * Get path to the canonical desktop UI JAR.
     */
    fun getDesktopJar(): Path {
        val projectRoot = getProjectRoot()
        val repoShellJar = projectRoot.resolve("apps/desktop-app-javafx/target/desktop-app-javafx-0.1.0-SNAPSHOT.jar")
        if (Files.exists(repoShellJar)) {
            return repoShellJar
        }

        newestMatchingJar(jarvisRoot.resolve("app"), "desktop-app-javafx-*.jar")?.let { return it }
        return repoShellJar
    }
    
    /**
     * Get API Gateway base URL for health checks.
     * Prod-first: HTTPS ingress is the default.
     *
     * Priority:
     * 1. Environment variable (JARVIS_API_BASE_URL or API_URL)
     * 2. Runtime-mode default (local -> localhost, k8s -> ingress)
     */
    fun getApiGatewayUrl(): String {
        val envUrl = normalizeBaseUrl(
            System.getenv("JARVIS_API_BASE_URL")
            ?: System.getenv("API_URL")
        )
        if (!envUrl.isNullOrBlank()) {
            return envUrl
        }

        val summary = loadRuntimeRunSummary()
        val summaryUrl = normalizeBaseUrl(summary?.apiUrl)
        val summaryMode = normalizeRuntimeMode(summary?.runtimeMode)
        val summaryStatus = summary?.status
        if (isLocalRuntime() &&
            summaryMode == LOCAL_RUNTIME_MODE &&
            !summaryStatus.equals("stopped", ignoreCase = true) &&
            !summaryUrl.isNullOrBlank()
        ) {
            return summaryUrl
        }

        return if (isLocalRuntime()) DEFAULT_LOCAL_API_GATEWAY else DEFAULT_REMOTE_API_GATEWAY
    }

    fun getGrafanaUrl(): String? {
        if (isLocalRuntime()) {
            return null
        }

        val summary = loadRuntimeRunSummary()
        val summaryUrl = normalizeBaseUrl(summary?.grafanaUrl)
        val summaryMode = normalizeRuntimeMode(summary?.runtimeMode)
        val summaryStatus = summary?.status
        if (summaryMode == K8S_RUNTIME_MODE &&
            !summaryStatus.equals("stopped", ignoreCase = true) &&
            !summaryUrl.isNullOrBlank()
        ) {
            return summaryUrl
        }

        return DEFAULT_REMOTE_GRAFANA
    }

    fun loadRuntimeRunSummary(): RuntimeRunSummary? {
        if (!Files.isRegularFile(lastRunSummary)) {
            return null
        }
        val raw = runCatching { Files.readString(lastRunSummary) }.getOrNull() ?: return null
        return RuntimeRunSummary(
            timestamp = extractJsonString(raw, "timestamp"),
            status = extractJsonString(raw, "status"),
            apiUrl = normalizeBaseUrl(extractJsonString(raw, "apiUrl")),
            voiceUrl = extractJsonString(raw, "voiceUrl")?.trim()?.trimEnd('/'),
            runtimeMode = extractJsonString(raw, "runtimeMode"),
            grafanaUrl = normalizeBaseUrl(extractJsonString(raw, "grafanaUrl"))
        )
    }

    fun describeRuntimeTarget(): String {
        val summary = loadRuntimeRunSummary()
        val summaryText = if (summary == null) {
            "last-run=missing"
        } else {
            "last-run(status=${summary.status ?: "unknown"}, api=${summary.apiUrl ?: "n/a"}, grafana=${summary.grafanaUrl ?: "n/a"}, mode=${summary.runtimeMode ?: "n/a"})"
        }
        return "runtimeMode=${getRuntimeMode()}, apiGateway=${getApiGatewayUrl()}, grafana=${getGrafanaUrl() ?: "n/a"}, $summaryText"
    }

    fun writeRuntimeRunSummary(
        status: String,
        apiUrl: String,
        voiceUrl: String,
        runtimeMode: String,
        grafanaUrl: String? = null
    ) {
        ensureDirectories()
        val payload = """
            {
              "timestamp": "${escapeJson(instantNow())}",
              "status": "${escapeJson(status)}",
              "apiUrl": "${escapeJson(normalizeBaseUrl(apiUrl) ?: apiUrl.trim())}",
              "voiceUrl": "${escapeJson(voiceUrl.trim().trimEnd('/'))}",
              "runtimeMode": "${escapeJson(runtimeMode)}",
              "grafanaUrl": "${escapeJson(grafanaUrl ?: "")}"
            }
        """.trimIndent()
        Files.writeString(lastRunSummary, payload)
    }

    private fun normalizeRuntimeMode(runtimeMode: String?): String? {
        return when (runtimeMode?.trim()?.lowercase()) {
            LOCAL_RUNTIME_MODE -> LOCAL_RUNTIME_MODE
            K8S_RUNTIME_MODE -> K8S_RUNTIME_MODE
            else -> null
        }
    }

    private fun isLocalUrl(url: String): Boolean {
        val normalized = url.trim().lowercase()
        return normalized.contains("127.0.0.1") || normalized.contains("localhost")
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private fun instantNow(): String = java.time.OffsetDateTime.now().toString()

    private fun normalizeBaseUrl(url: String?): String? {
        val trimmed = url?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains("jarvis.local", ignoreCase = true) -> "https://$trimmed"
            else -> "http://$trimmed"
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    private fun newestMatchingJar(directory: Path, glob: String): Path? {
        if (!Files.isDirectory(directory)) {
            return null
        }

        return runCatching {
            Files.newDirectoryStream(directory, glob).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .maxByOrNull(::lastModifiedSafe)
            }
        }.getOrNull()
    }

    private fun lastModifiedSafe(path: Path): FileTime {
        return runCatching { Files.getLastModifiedTime(path) }.getOrDefault(FileTime.fromMillis(Long.MIN_VALUE))
    }
}
