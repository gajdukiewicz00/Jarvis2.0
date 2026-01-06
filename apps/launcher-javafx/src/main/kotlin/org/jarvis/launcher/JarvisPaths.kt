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
    private val userHome: String = System.getProperty("user.home")
    private val jarvisRoot: Path = Paths.get(userHome, ".jarvis")
    
    val root: Path = jarvisRoot
    val logs: Path = jarvisRoot.resolve("logs")
    val run: Path = jarvisRoot.resolve("run")
    val data: Path = jarvisRoot.resolve("data")
    
    // Log files
    val launcherLog: Path = logs.resolve("launcher.log")
    val backendLaunchLog: Path = logs.resolve("backend-launch.log")
    val desktopLog: Path = logs.resolve("desktop.log")
    val installLog: Path = logs.resolve("install.log")  // Stage 12
    
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
        listOf(logs, run, data).forEach { dir ->
            if (!Files.exists(dir)) {
                Files.createDirectories(dir)
            }
        }
    }
    
    /**
     * Get project root directory (where jarvis-launch.sh is located).
     */
    fun getProjectRoot(): Path {
        // Try environment variable first (set by launcher or user)
        val envRoot = System.getenv("JARVIS_PROJECT_ROOT")
        if (envRoot != null) {
            val root = Paths.get(envRoot)
            if (Files.exists(root.resolve("jarvis-launch.sh"))) {
                return root
            }
        }
        
        // Try to find project root by looking for jarvis-launch.sh
        val currentDir = Paths.get(System.getProperty("user.dir"))
        val launchScript = currentDir.resolve("jarvis-launch.sh")
        if (Files.exists(launchScript) && Files.isExecutable(launchScript)) {
            return currentDir
        }
        
        // Fallback: assume we're in apps/launcher-javafx, go up 2 levels
        val assumedRoot = currentDir.parent?.parent
        if (assumedRoot != null && Files.exists(assumedRoot.resolve("jarvis-launch.sh"))) {
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
            if (Files.exists(path.resolve("jarvis-launch.sh"))) {
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
        return getProjectRoot().resolve("jarvis-launch.sh")
    }
    
    /**
     * Get path to jarvis-stop.sh script.
     */
    fun getStopScript(): Path {
        return getProjectRoot().resolve("jarvis-stop.sh")
    }
    
    /**
     * Get path to desktop-client JAR.
     */
    fun getDesktopJar(): Path {
        return getProjectRoot().resolve("apps/desktop-client-javafx/target/desktop-client-javafx-0.1.0-SNAPSHOT.jar")
    }
    
    /**
     * Get API Gateway base URL for health checks.
     * Stage 8: Ingress/HTTPS as default, NodePort/port-forward as fallback.
     * 
     * Priority:
     * 1. Environment variable (JARVIS_API_BASE_URL or API_URL)
     * 2. TLS/Ingress detection (JARVIS_USE_TLS or ingress with api.jarvis.local)
     * 3. Fallback to NodePort/port-forward (legacy)
     */
    fun getApiGatewayUrl(): String {
        // Try environment variable first
        val envUrl = System.getenv("JARVIS_API_BASE_URL") 
            ?: System.getenv("API_URL")
        if (envUrl != null && envUrl.isNotBlank()) {
            val url = envUrl.trimEnd('/')
            // Auto-detect TLS: if domain contains jarvis.local, use HTTPS
            if (url.contains("jarvis.local") && !url.startsWith("http")) {
                return "https://$url"
            }
            return url
        }
        
        // Stage 8: Check if TLS/Ingress is active
        val useTls = System.getenv("JARVIS_USE_TLS")?.toBoolean() ?: false
        if (useTls) {
            return "https://api.jarvis.local"
        }
        
        // Stage 8: Try to detect ingress (best-effort, may not work if kubectl unavailable)
        try {
            val process = ProcessBuilder("kubectl", "get", "ingress", "jarvis-ingress", "-n", "jarvis", "-o", "jsonpath={.spec.rules[*].host}")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                val output = process.inputStream.bufferedReader().readText().trim()
                if (output.contains("api.jarvis.local")) {
                    // Ingress exists with api.jarvis.local -> use HTTPS
                    return "https://api.jarvis.local"
                }
            }
        } catch (e: Exception) {
            // kubectl not available or error - fall through to fallback
        }
        
        // Fallback: legacy NodePort/port-forward (for backward compatibility)
        return "http://localhost:8080"
    }
}

