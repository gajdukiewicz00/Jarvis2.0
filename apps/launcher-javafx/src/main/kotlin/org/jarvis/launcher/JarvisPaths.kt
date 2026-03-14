package org.jarvis.launcher

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Centralized paths for Jarvis product data.
 * All paths are under ~/.jarvis/
 */
object JarvisPaths {
    private const val LOCAL_RUNTIME_MODE = "local"
    private const val K8S_RUNTIME_MODE = "k8s"
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
    
    // Stage 12: Release source tracking
    val releaseSourceFile: Path = jarvisRoot.resolve("app").resolve("RELEASE_SOURCE")
    
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

        // Try environment variable first (set by launcher or user)
        val envRoot = System.getenv("JARVIS_PROJECT_ROOT")
        if (envRoot != null) {
            val root = Paths.get(envRoot)
            if (isValidProjectRoot(root)) {
                return root
            }
        }
        
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

        return K8S_RUNTIME_MODE
    }
    
    /**
     * Get path to desktop-client JAR.
     */
    fun getDesktopJar(): Path {
        val appDir = jarvisRoot.resolve("app")
        if (Files.exists(appDir)) {
            try {
                Files.newDirectoryStream(appDir, "desktop-client-javafx-*.jar").use { stream ->
                    val iterator = stream.iterator()
                    if (iterator.hasNext()) {
                        return iterator.next()
                    }
                }
            } catch (e: Exception) {
                // Ignore and fall back to repo path
            }
        }
        return getProjectRoot().resolve("apps/desktop-client-javafx/target/desktop-client-javafx-0.1.0-SNAPSHOT.jar")
    }
    
    /**
     * Get API Gateway base URL for health checks.
     * Prod-first: HTTPS ingress is the default.
     *
     * Priority:
     * 1. Environment variable (JARVIS_API_BASE_URL or API_URL)
     * 2. Last run summary (~/.jarvis/run/last-run.json)
     * 3. Default HTTPS ingress (https://api.jarvis.local)
     */
    fun getApiGatewayUrl(): String {
        val envUrl = System.getenv("JARVIS_API_BASE_URL")
            ?: System.getenv("API_URL")
        if (!envUrl.isNullOrBlank()) {
            val url = envUrl.trimEnd('/')
            return if (url.contains("jarvis.local") && !url.startsWith("http")) {
                "https://$url"
            } else {
                url
            }
        }

        val runSummary = run.resolve("last-run.json")
        if (Files.exists(runSummary)) {
            try {
                val content = Files.readString(runSummary)
                val match = Regex("\"apiUrl\"\\s*:\\s*\"([^\"]+)\"").find(content)
                val apiUrl = match?.groups?.get(1)?.value
                if (!apiUrl.isNullOrBlank()) {
                    return apiUrl.trimEnd('/')
                }
            } catch (e: Exception) {
                // Ignore malformed summary
            }
        }

        return if (isLocalRuntime()) "http://127.0.0.1:8080" else "https://api.jarvis.local"
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
}
