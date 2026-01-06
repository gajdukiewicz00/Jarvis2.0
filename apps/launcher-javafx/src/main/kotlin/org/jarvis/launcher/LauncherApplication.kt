package org.jarvis.launcher

import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * JavaFX Application for Jarvis Launcher.
 * Provides GUI for starting/stopping backend and desktop client.
 */
class LauncherApplication : Application() {
    private val logger = LoggerFactory.getLogger(LauncherApplication::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    
    private lateinit var statusLabel: Label
    private lateinit var versionLabel: Label
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var startAllButton: Button  // Stage 5
    private lateinit var stopAllButton: Button  // Stage 5
    private lateinit var openLogsButton: Button
    private lateinit var diagnosticsButton: Button
    private lateinit var copyDiagnosticsButton: Button  // Stage 15: Copy diagnostics to clipboard
    private lateinit var startDesktopButton: Button
    private lateinit var statusLogArea: TextArea  // Simple status log in Control tab
    private lateinit var statusBadge: Label  // Stage 15: Status badge with colors
    
    // Stage 5: Desktop process tracking
    private var desktopProcess: Process? = null
    private val desktopProcessLock = java.util.concurrent.locks.ReentrantLock()
    
    private val processRunner = AtomicReference<ProcessRunner?>(null)
    private val status = AtomicReference<LauncherStatus>(LauncherStatus.IDLE)
    private var healthCheckService: HealthCheckService? = null
    private var healthCheckTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val healthCheckExecutor = Executors.newScheduledThreadPool(1)
    
    // Stage 4: Log Viewer and Diagnostics
    private var logViewer: LogViewer? = null
    private val diagnosticsCollector = DiagnosticsCollector()
    
    enum class LauncherStatus {
        IDLE,           // Backend not started
        STARTING,       // Backend starting, health checks not OK yet
        READY,          // All core services UP
        DEGRADED,       // Core UP, but optional services down
        ERROR           // Core services down or fatal error
    }
    
    override fun start(primaryStage: Stage) {
        logger.info("🚀 Starting Jarvis 2.0 Launcher...")
        
        // Ensure directories exist
        JarvisPaths.ensureDirectories()
        
        // Setup UI
        val root = createUI()
        val scene = Scene(root, 800.0, 600.0)
        
        primaryStage.title = "Jarvis 2.0 Launcher"
        primaryStage.scene = scene
        primaryStage.setOnCloseRequest {
            logger.info("Launcher closing...")
            healthCheckTask?.cancel(false)
            healthCheckExecutor.shutdown()
            logViewer?.stop()
            
            // Stage 5: Cleanup desktop process
            desktopProcessLock.lock()
            try {
                desktopProcess?.destroyForcibly()
                desktopProcess = null
            } finally {
                desktopProcessLock.unlock()
            }
            
            processRunner.get()?.stop()
            Platform.exit()
        }
        
        primaryStage.show()
        
        // Initialize health check service
        val apiUrl = JarvisPaths.getApiGatewayUrl()
        healthCheckService = HealthCheckService(apiUrl) { healthStatus ->
            Platform.runLater {
                updateStatusFromHealth(healthStatus)
            }
        }
        
        // Check if backend is already running
        checkBackendStatus()
        
        // Stage 5: Cleanup stale PIDs on startup
        cleanupStalePids()
        
        // Start periodic health checks (every 5 seconds)
        startHealthChecks()
    }
    
    private fun startHealthChecks() {
        healthCheckTask = healthCheckExecutor.scheduleAtFixedRate({
            try {
                val pid = processRunner.get()?.getPid() ?: getBackendPidFromFile()
                val healthStatus = healthCheckService?.checkHealth(pid)
                if (healthStatus != null) {
                    Platform.runLater {
                        updateStatusFromHealth(healthStatus)
                    }
                }
            } catch (e: Exception) {
                logger.error("Health check error", e)
                // Don't let exceptions break the polling loop
            }
        }, 5, 5, TimeUnit.SECONDS)
    }
    
    private fun getBackendPidFromFile(): Long? {
        return try {
            val pidFile = JarvisPaths.backendPid
            if (Files.exists(pidFile)) {
                Files.readString(pidFile).trim().toLongOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun updateStatusFromHealth(healthStatus: HealthCheckService.ServiceHealthStatus) {
        val newLauncherStatus = when (healthStatus.overall) {
            HealthCheckService.ServiceHealthStatus.OverallStatus.IDLE -> LauncherStatus.IDLE
            HealthCheckService.ServiceHealthStatus.OverallStatus.STARTING -> LauncherStatus.STARTING
            HealthCheckService.ServiceHealthStatus.OverallStatus.READY -> LauncherStatus.READY
            HealthCheckService.ServiceHealthStatus.OverallStatus.DEGRADED -> LauncherStatus.DEGRADED
            HealthCheckService.ServiceHealthStatus.OverallStatus.ERROR -> LauncherStatus.ERROR
        }
        
        // Stage 4: Human-readable, action-oriented status messages
        val statusText = when (healthStatus.overall) {
            HealthCheckService.ServiceHealthStatus.OverallStatus.READY -> {
                "READY\n${healthStatus.reasons.joinToString("\n")}"
            }
            HealthCheckService.ServiceHealthStatus.OverallStatus.DEGRADED -> {
                val optionalIssues = healthStatus.optionalServices.values
                    .filter { it.status == HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN }
                    .map { "${it.name}: ${it.message}" }
                "DEGRADED\nCore services OK, but optional services unavailable:\n${optionalIssues.joinToString("\n")}\n\nDesktop is available, but some features may not work."
            }
            HealthCheckService.ServiceHealthStatus.OverallStatus.STARTING -> {
                val coreIssues = healthStatus.coreServices.values
                    .filter { it.status != HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP }
                    .map { "${it.name}: ${it.message}" }
                if (coreIssues.isEmpty()) {
                    "STARTING...\nWaiting for services to become ready..."
                } else {
                    "STARTING...\n${coreIssues.joinToString("\n")}\n\nBackend is starting, please wait..."
                }
            }
            HealthCheckService.ServiceHealthStatus.OverallStatus.ERROR -> {
                // Stage 4: Action-oriented error messages
                val errorMessage = buildErrorMessage(healthStatus)
                "ERROR\n$errorMessage"
            }
            HealthCheckService.ServiceHealthStatus.OverallStatus.IDLE -> {
                "IDLE\nBackend not running. Click 'Start Backend' to begin."
            }
        }
        
        Platform.runLater {
            statusLabel.text = statusText
            updateStatus(newLauncherStatus)
            updateStatusBadge(newLauncherStatus)  // Stage 15: Update badge color
        }
    }
    
    /**
     * Build human-readable, action-oriented error message.
     */
    private fun buildErrorMessage(healthStatus: HealthCheckService.ServiceHealthStatus): String {
        val apiGateway = healthStatus.coreServices["api-gateway"]
        val securityService = healthStatus.coreServices["security-service"]
        
        val messages = mutableListOf<String>()
        
        when {
            apiGateway?.status == HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN -> {
                when {
                    apiGateway.message.contains("Connection refused") -> {
                        messages.add("API Gateway not reachable — backend may still be starting")
                        messages.add("Action: Wait 10-15 seconds, then check again")
                    }
                    apiGateway.message.contains("Timeout") -> {
                        messages.add("API Gateway timeout — service may be overloaded")
                        messages.add("Action: Check backend logs for errors")
                    }
                    apiGateway.message.contains("HTTP") -> {
                        messages.add("API Gateway returned error: ${apiGateway.message}")
                        messages.add("Action: Check backend logs and Kubernetes pods")
                    }
                    else -> {
                        messages.add("API Gateway: ${apiGateway.message}")
                        messages.add("Action: Check if backend process is running")
                    }
                }
            }
            securityService?.status == HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN -> {
                when {
                    securityService.message.contains("database") || securityService.message.contains("db") -> {
                        messages.add("Security Service DOWN — database connection issue")
                        messages.add("Action: Check database pod status: kubectl get pods -n jarvis | grep postgres")
                    }
                    securityService.message.contains("Connection") -> {
                        messages.add("Security Service not reachable — may be starting")
                        messages.add("Action: Wait a few seconds and check again")
                    }
                    else -> {
                        messages.add("Security Service: ${securityService.message}")
                        messages.add("Action: Check security-service logs")
                    }
                }
            }
            else -> {
                // Generic error
                messages.add("Core services are not healthy")
                healthStatus.reasons.take(3).forEach { messages.add("  - $it") }
                messages.add("Action: Check backend logs and Kubernetes status")
            }
        }
        
        // Add backend process check
        val backendPid = processRunner.get()?.getPid() ?: getBackendPidFromFile()
        if (backendPid == null) {
            messages.add("")
            messages.add("Backend process stopped unexpectedly")
            messages.add("Action: Click 'Start Backend' to restart")
        }
        
        return messages.joinToString("\n")
    }
    
    private fun createUI(): VBox {
        val root = VBox(10.0)
        root.padding = Insets(15.0)
        
        // Stage 15: Header with app title
        val header = HBox(10.0)
        header.alignment = Pos.CENTER_LEFT
        val titleLabel = Label("Jarvis 2.0 Launcher")
        titleLabel.style = "-fx-font-size: 18pt; -fx-font-weight: bold;"
        
        // Stage 15: Status badge (colored)
        statusBadge = Label("IDLE")
        statusBadge.style = "-fx-font-weight: bold; -fx-font-size: 12pt; -fx-padding: 5 10 5 10; -fx-background-radius: 5;"
        updateStatusBadge(LauncherStatus.IDLE)
        
        // Version info
        versionLabel = Label(loadVersion())
        val versionText = loadVersion()
        versionLabel.text = versionText
        if (versionText.contains("⚠️")) {
            versionLabel.style = "-fx-font-size: 10px; -fx-text-fill: orange; -fx-font-weight: bold;"
        } else {
            versionLabel.style = "-fx-font-size: 10px; -fx-text-fill: gray;"
        }
        
        header.children.addAll(titleLabel, statusBadge, versionLabel)
        
        // Status area (detailed status text)
        val statusBox = HBox(10.0)
        statusBox.alignment = Pos.CENTER_LEFT
        val statusText = Label("Status:")
        statusLabel = Label("IDLE")
        statusLabel.style = "-fx-font-weight: bold;"
        statusBox.children.addAll(statusText, statusLabel)
        
        // Buttons
        val buttonBox = HBox(10.0)
        buttonBox.alignment = Pos.CENTER
        
        startButton = Button("Start Backend")
        startButton.setOnAction { startBackend() }
        
        stopButton = Button("Stop Backend")
        stopButton.isDisable = true
        stopButton.setOnAction { stopBackend() }
        
        startDesktopButton = Button("Start Desktop")
        startDesktopButton.isDisable = true
        startDesktopButton.setOnAction { startDesktop() }
        
        // Stage 5: Start All / Stop All buttons
        startAllButton = Button("Start All")
        startAllButton.setOnAction { startAll() }
        
        stopAllButton = Button("Stop All")
        stopAllButton.isDisable = true
        stopAllButton.setOnAction { stopAll() }
        
        // Stage 15: Add tooltips to all buttons
        startButton.tooltip = Tooltip("Start the Jarvis backend stack")
        stopButton.tooltip = Tooltip("Stop the Jarvis backend stack")
        startAllButton.tooltip = Tooltip("Start backend and wait for READY, then start desktop client")
        stopAllButton.tooltip = Tooltip("Stop desktop client and backend")
        startDesktopButton.tooltip = Tooltip("Start the desktop client (requires backend READY)")
        
        openLogsButton = Button("Open Logs Folder")
        openLogsButton.tooltip = Tooltip("Open ~/.jarvis/logs/ in file manager")
        openLogsButton.setOnAction { openLogsFolder() }
        
        // Stage 12: Release Info buttons
        val openInstallLogButton = Button("Open Install Log")
        openInstallLogButton.tooltip = Tooltip("Open installation log file")
        openInstallLogButton.setOnAction { openInstallLog() }
        
        val openReleaseFolderButton = Button("Open Release Folder")
        openReleaseFolderButton.tooltip = Tooltip("Open release source folder")
        openReleaseFolderButton.setOnAction { openReleaseFolder() }
        
        diagnosticsButton = Button("Collect Diagnostics")
        diagnosticsButton.tooltip = Tooltip("Collect diagnostics snapshot and save to file")
        diagnosticsButton.setOnAction { collectDiagnostics() }
        
        // Stage 15: Copy Diagnostics button
        copyDiagnosticsButton = Button("Copy Diagnostics")
        copyDiagnosticsButton.tooltip = Tooltip("Copy diagnostics snapshot to clipboard (masked)")
        copyDiagnosticsButton.setOnAction { copyDiagnostics() }
        
        buttonBox.children.addAll(startButton, stopButton, startAllButton, stopAllButton, startDesktopButton, openLogsButton, openInstallLogButton, openReleaseFolderButton, diagnosticsButton, copyDiagnosticsButton)
        
        // Stage 4: TabPane with Control and Logs tabs
        val tabPane = TabPane()
        
        // Control Tab
        val controlTab = Tab("Control")
        val controlContent = VBox(10.0)
        controlContent.padding = Insets(10.0)
        
        // Simple status log area for Control tab (quick messages)
        val statusLogArea = TextArea()
        statusLogArea.isEditable = false
        statusLogArea.style = "-fx-font-family: monospace; -fx-font-size: 10pt;"
        statusLogArea.prefRowCount = 8
        val statusLogScroll = ScrollPane(statusLogArea)
        statusLogScroll.isFitToWidth = true
        statusLogScroll.isFitToHeight = true
        
        controlContent.children.addAll(header, statusBox, buttonBox, statusLogScroll)
        VBox.setVgrow(statusLogScroll, Priority.ALWAYS)
        controlTab.content = controlContent
        controlTab.isClosable = false
        
        // Logs Tab (Stage 4) - Full log viewer
        val logsTab = Tab("Logs")
        val logViewerInstance = LogViewer()
        logViewer = logViewerInstance
        logsTab.content = logViewerInstance.createUI()
        logsTab.isClosable = false
        
        tabPane.tabs.addAll(controlTab, logsTab)
        VBox.setVgrow(tabPane, Priority.ALWAYS)
        
        root.children.add(tabPane)
        VBox.setVgrow(root, Priority.ALWAYS)
        
        // Store statusLogArea for appendLog
        this.statusLogArea = statusLogArea
        
        return root
    }
    
    private fun checkBackendStatus() {
        executor.execute {
            val pidFile = JarvisPaths.backendPid
            if (Files.exists(pidFile)) {
                try {
                    val pid = Files.readString(pidFile).trim().toLong()
                    // Check if process is alive using ProcessHandle (Java 9+)
                    val processHandle = try {
                        ProcessHandle.of(pid).orElse(null)
                    } catch (e: Exception) {
                        null
                    }
                    
                    if (processHandle != null && processHandle.isAlive) {
                        Platform.runLater {
                            updateStatus(LauncherStatus.READY)
                            appendLog("Backend already running (PID: $pid)")
                        }
                    } else {
                        // Stale PID file
                        Files.deleteIfExists(pidFile)
                        Platform.runLater {
                            appendLog("Stale PID file removed")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Error checking backend status", e)
                    // Remove invalid PID file
                    try {
                        Files.deleteIfExists(pidFile)
                    } catch (e2: Exception) {
                        // Ignore
                    }
                }
            }
        }
    }
    
    private fun startBackend() {
        // Check if already running
        val existingRunner = processRunner.get()
        if (existingRunner != null && existingRunner.isRunning()) {
            appendLog("Backend already running (PID: ${existingRunner.getPid()})")
            updateStatus(LauncherStatus.READY)
            return
        }
        
        // Check PID file for stale process
        val pidFile = JarvisPaths.backendPid
        if (Files.exists(pidFile)) {
            try {
                val pid = Files.readString(pidFile).trim().toLong()
                val processHandle = try {
                    ProcessHandle.of(pid).orElse(null)
                } catch (e: Exception) {
                    null
                }
                
                if (processHandle == null || !processHandle.isAlive) {
                    // Stale PID file - clean it up
                    Files.deleteIfExists(pidFile)
                    appendLog("Cleaned up stale PID file")
                } else {
                    // Process is actually running
                    appendLog("Backend already running (detected via PID file: $pid)")
                    updateStatus(LauncherStatus.READY)
                    return
                }
            } catch (e: Exception) {
                // Invalid PID file - clean it up
                Files.deleteIfExists(pidFile)
                appendLog("Cleaned up invalid PID file")
            }
        }
        
        val launchScript = JarvisPaths.getLaunchScript()
        
        if (!Files.exists(launchScript)) {
            showError("Launch script not found", "Cannot find: $launchScript\n\nPlease ensure JARVIS_PROJECT_ROOT is set correctly or run launcher from project directory.")
            return
        }
        
        if (!Files.isExecutable(launchScript)) {
            val fixCommand = "chmod +x \"$launchScript\""
            showError(
                "Launch script not executable",
                "The script $launchScript is not executable.\n\n" +
                "To fix, run in terminal:\n$fixCommand\n\n" +
                "Or click 'Diagnostics' for more information."
            )
            return
        }
        
        updateStatus(LauncherStatus.STARTING)
        appendLog("Starting backend...")
        appendLog("Script: $launchScript")
        
        val newRunner = ProcessRunner(
            logFile = JarvisPaths.backendLaunchLog,
            onOutput = { line ->
                Platform.runLater {
                    appendLog(line)
                }
            }
        )
        
        processRunner.set(newRunner)
        
        executor.execute {
            val envVars = mapOf(
                "ENABLE_LLM" to "false",
                "ENABLE_MEMORY" to "false"
            )
            
            val future = newRunner.start(
                scriptPath = launchScript,
                envVars = envVars,
                workingDir = launchScript.parent
            )
            
            future.whenComplete { exitCode, throwable ->
                Platform.runLater {
                    if (throwable != null) {
                        logger.error("Backend start failed", throwable)
                        updateStatus(LauncherStatus.ERROR)
                        appendLog("ERROR: ${throwable.message}")
                        showError(
                            "Backend start failed",
                            "${throwable.message ?: "Unknown error"}\n\n" +
                            "Check logs: ${JarvisPaths.backendLaunchLog}\n" +
                            "Click 'Open Logs' to view details."
                        )
                    } else {
                        if (exitCode == 0) {
                            updateStatus(LauncherStatus.READY)
                            appendLog("Backend started successfully")
                            appendLog("Status: READY - You can now start Desktop")
                        } else {
                            updateStatus(LauncherStatus.ERROR)
                            appendLog("Backend exited with code: $exitCode")
                            showError(
                                "Backend failed",
                                "Exit code: $exitCode\n\n" +
                                "Check logs: ${JarvisPaths.backendLaunchLog}\n" +
                                "Click 'Open Logs' to view details."
                            )
                        }
                    }
                }
            }
        }
    }
    
    private fun stopBackend() {
        appendLog("Stopping backend...")
        
        val runner = processRunner.get()
        if (runner != null) {
            executor.execute {
                val stopped = runner.stop()
                Platform.runLater {
                    if (stopped) {
                        updateStatus(LauncherStatus.IDLE)
                        appendLog("Backend stopped")
                    } else {
                        appendLog("Failed to stop backend")
                    }
                }
            }
        } else {
            // Try to call jarvis-stop.sh
            val stopScript = JarvisPaths.getStopScript()
            if (Files.exists(stopScript) && Files.isExecutable(stopScript)) {
                executor.execute {
                    try {
                        val process = ProcessBuilder(stopScript.toString())
                            .directory(stopScript.parent.toFile())
                            .start()
                        
                        val exitCode = process.waitFor()
                        Platform.runLater {
                            updateStatus(LauncherStatus.IDLE)
                            appendLog("Backend stopped via script (exit code: $exitCode)")
                        }
                    } catch (e: Exception) {
                        logger.error("Error stopping backend", e)
                        Platform.runLater {
                            appendLog("ERROR: ${e.message}")
                        }
                    }
                }
            } else {
                appendLog("Stop script not found: $stopScript")
            }
        }
    }
    
    private fun startDesktop() {
        // Stage 5: Check if desktop already running
        if (isDesktopRunning()) {
            appendLog("Desktop client already running")
            showError("Desktop already running", "Desktop client is already started. Check if window is open.")
            return
        }
        
        val desktopJar = JarvisPaths.getDesktopJar()
        
        if (!Files.exists(desktopJar)) {
            showError("Desktop JAR not found", "Cannot find: $desktopJar\nPlease build desktop-client first:\nmvn -pl apps/desktop-client-javafx -DskipTests clean package")
            return
        }
        
        appendLog("Starting desktop client...")
        
        executor.execute {
            try {
                val process = ProcessBuilder(
                    "java",
                    "-jar",
                    desktopJar.toString(),
                    "-Dlogback.configurationFile=${JarvisPaths.logs.resolve("logback-desktop.xml")}"
                )
                    .directory(desktopJar.parent.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(JarvisPaths.desktopLog.toFile()))
                    .redirectError(ProcessBuilder.Redirect.appendTo(JarvisPaths.desktopLog.toFile()))
                    .start()
                
                // Stage 5: Save desktop PID
                desktopProcessLock.lock()
                try {
                    desktopProcess = process
                    Files.writeString(JarvisPaths.desktopPid, process.pid().toString())
                } finally {
                    desktopProcessLock.unlock()
                }
                
                appendLog("Desktop client started (PID: ${process.pid()})")
            } catch (e: Exception) {
                logger.error("Failed to start desktop client", e)
                Platform.runLater {
                    appendLog("ERROR: ${e.message}")
                    showError("Desktop start failed", e.message ?: "Unknown error")
                }
            }
        }
    }
    
    /**
     * Stage 5: Check if desktop is already running.
     */
    private fun isDesktopRunning(): Boolean {
        return try {
            val pidFile = JarvisPaths.desktopPid
            if (Files.exists(pidFile)) {
                val pid = Files.readString(pidFile).trim().toLongOrNull()
                if (pid != null) {
                    val isAlive = try {
                        java.lang.ProcessHandle.of(pid)
                            .map { it.isAlive }
                            .orElse(false)
                    } catch (e: Exception) {
                        false
                    }
                    if (!isAlive) {
                        // Stale PID file
                        Files.deleteIfExists(pidFile)
                        return false
                    }
                    return true
                }
            }
            false
        } catch (e: Exception) {
            logger.warn("Error checking desktop PID", e)
            false
        }
    }
    
    /**
     * Stage 5: Stop desktop client.
     */
    private fun stopDesktop() {
        executor.execute {
            desktopProcessLock.lock()
            try {
                val pidFile = JarvisPaths.desktopPid
                var pid: Long? = null
                
                // Try to get PID from process
                if (desktopProcess != null) {
                    try {
                        pid = desktopProcess!!.pid()
                    } catch (e: Exception) {
                        // Process may have already terminated
                    }
                }
                
                // Fallback to PID file
                if (pid == null && Files.exists(pidFile)) {
                    try {
                        pid = Files.readString(pidFile).trim().toLongOrNull()
                    } catch (e: Exception) {
                        logger.warn("Error reading desktop PID file", e)
                    }
                }
                
                if (pid != null) {
                    val isAlive = try {
                        java.lang.ProcessHandle.of(pid)
                            .map { it.isAlive }
                            .orElse(false)
                    } catch (e: Exception) {
                        false
                    }
                    
                    if (isAlive) {
                        appendLog("Stopping desktop client (PID: $pid)...")
                        try {
                            val processHandle = java.lang.ProcessHandle.of(pid).orElse(null)
                            processHandle?.destroy()
                            
                            // Wait up to 5 seconds for graceful shutdown
                            var waited = 0
                            while (waited < 5000 && processHandle?.isAlive == true) {
                                Thread.sleep(500)
                                waited += 500
                            }
                            
                            if (processHandle?.isAlive == true) {
                                appendLog("Desktop did not stop gracefully, forcing...")
                                processHandle.destroyForcibly()
                            }
                            
                            appendLog("Desktop client stopped")
                        } catch (e: Exception) {
                            logger.error("Error stopping desktop", e)
                            appendLog("ERROR stopping desktop: ${e.message}")
                        }
                    } else {
                        appendLog("Desktop process not running (stale PID: $pid)")
                    }
                } else {
                    appendLog("No desktop PID found")
                }
                
                // Cleanup
                desktopProcess = null
                Files.deleteIfExists(pidFile)
            } finally {
                desktopProcessLock.unlock()
            }
        }
    }
    
    /**
     * Stage 5: Start All - backend → wait READY → desktop.
     */
    private fun startAll() {
        // Idempotency: check if already running
        val currentStatus = status.get()
        if (currentStatus == LauncherStatus.READY || currentStatus == LauncherStatus.DEGRADED) {
            // Backend already ready, just start desktop
            if (!isDesktopRunning()) {
                appendLog("Backend already READY, starting desktop...")
                startDesktop()
            } else {
                appendLog("Desktop already running")
            }
            return
        }
        
        if (currentStatus == LauncherStatus.STARTING) {
            appendLog("Backend is already starting, please wait...")
            return
        }
        
        // Start backend first
        appendLog("Start All: Starting backend...")
        startBackend()
        
        // Wait for READY/DEGRADED with timeout
        executor.execute {
            val startTime = System.currentTimeMillis()
            val timeoutMs = 90_000L  // 90 seconds
            
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val currentStatus = status.get()
                if (currentStatus == LauncherStatus.READY || currentStatus == LauncherStatus.DEGRADED) {
                    Platform.runLater {
                        appendLog("Backend is READY, starting desktop...")
                        startDesktop()
                    }
                    return@execute
                }
                
                if (currentStatus == LauncherStatus.ERROR) {
                    Platform.runLater {
                        appendLog("ERROR: Backend failed to start")
                        showError(
                            "Backend failed to start",
                            "Backend did not become READY within timeout.\n\n" +
                            "Action: Click 'Collect Diagnostics' to see details.\n" +
                            "Check logs in the Logs tab for errors."
                        )
                    }
                    return@execute
                }
                
                Thread.sleep(2000)  // Check every 2 seconds
            }
            
            // Timeout
            Platform.runLater {
                appendLog("ERROR: Timeout waiting for backend READY (90s)")
                showError(
                    "Timeout waiting for backend",
                    "Backend did not become READY within 90 seconds.\n\n" +
                    "Action: Click 'Collect Diagnostics' to see details.\n" +
                    "Check logs in the Logs tab for errors.\n" +
                    "You can try 'Start Backend' separately to debug."
                )
            }
        }
    }
    
    /**
     * Stage 5: Stop All - desktop → backend → cleanup.
     */
    private fun stopAll() {
        appendLog("Stop All: Stopping desktop and backend...")
        
        // Idempotency: check if anything is running
        val currentStatus = status.get()
        val desktopRunning = isDesktopRunning()
        
        if (!desktopRunning && (currentStatus == LauncherStatus.IDLE || currentStatus == LauncherStatus.ERROR)) {
            appendLog("Nothing to stop (desktop and backend already stopped)")
            return
        }
        
        executor.execute {
            // 1. Stop desktop first
            if (desktopRunning) {
                appendLog("Stopping desktop...")
                stopDesktop()
                Thread.sleep(2000)  // Give desktop time to stop
            } else {
                appendLog("Desktop not running")
            }
            
            // 2. Stop backend
            if (currentStatus != LauncherStatus.IDLE) {
                appendLog("Stopping backend...")
                stopBackend()
            } else {
                appendLog("Backend not running")
            }
            
            // 3. Cleanup stale PIDs
            cleanupStalePids()
            
            Platform.runLater {
                appendLog("Stop All completed")
                updateStatus(LauncherStatus.IDLE)
            }
        }
    }
    
    /**
     * Stage 5: Cleanup stale PID files.
     */
    private fun cleanupStalePids() {
        try {
            // Check backend PID
            if (Files.exists(JarvisPaths.backendPid)) {
                val pid = Files.readString(JarvisPaths.backendPid).trim().toLongOrNull()
                if (pid != null) {
                    val isAlive = try {
                        java.lang.ProcessHandle.of(pid)
                            .map { it.isAlive }
                            .orElse(false)
                    } catch (e: Exception) {
                        false
                    }
                    if (!isAlive) {
                        Files.deleteIfExists(JarvisPaths.backendPid)
                        appendLog("Cleaned up stale backend PID")
                    }
                }
            }
            
            // Check desktop PID
            if (Files.exists(JarvisPaths.desktopPid)) {
                val pid = Files.readString(JarvisPaths.desktopPid).trim().toLongOrNull()
                if (pid != null) {
                    val isAlive = try {
                        java.lang.ProcessHandle.of(pid)
                            .map { it.isAlive }
                            .orElse(false)
                    } catch (e: Exception) {
                        false
                    }
                    if (!isAlive) {
                        Files.deleteIfExists(JarvisPaths.desktopPid)
                        appendLog("Cleaned up stale desktop PID")
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Error cleaning up stale PIDs", e)
        }
    }
    
    // Stage 15: Renamed to openLogsFolder for clarity
    private fun openLogsFolder() {
        executor.execute {
            try {
                val logsDir = JarvisPaths.logs.toFile()
                if (logsDir.exists()) {
                    // Try to open file manager (Linux)
                    ProcessBuilder("xdg-open", logsDir.absolutePath).start()
                } else {
                    Platform.runLater {
                        showError("Logs directory not found", "Directory does not exist: ${logsDir.absolutePath}\n\nIt will be created when logs are first written.")
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to open logs directory", e)
                Platform.runLater {
                    showError("Failed to open logs folder", "Could not open logs directory.\n\nAction: Open manually: ${JarvisPaths.logs.toFile().absolutePath}")
                }
            }
        }
    }
    
    // Stage 15: Copy diagnostics to clipboard (masked)
    private fun copyDiagnostics() {
        copyDiagnosticsButton.isDisable = true
        copyDiagnosticsButton.text = "Copying..."
        
        executor.execute {
            try {
                val backendPid = processRunner.get()?.getPid() ?: getBackendPidFromFile()
                val healthStatus = healthCheckService?.getCurrentStatus()
                val version = loadVersion()
                
                // Collect diagnostics text (same as DiagnosticsCollector but in-memory)
                val diagnostics = StringBuilder()
                diagnostics.appendLine("=".repeat(60))
                diagnostics.appendLine("Jarvis 2.0 Diagnostics Snapshot")
                diagnostics.appendLine("=".repeat(60))
                diagnostics.appendLine("Timestamp: ${java.time.LocalDateTime.now()}")
                diagnostics.appendLine("")
                diagnostics.appendLine("--- Launcher ---")
                diagnostics.appendLine("Version: $version")
                diagnostics.appendLine("")
                diagnostics.appendLine("--- System ---")
                diagnostics.appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                diagnostics.appendLine("Java: ${System.getProperty("java.version")}")
                diagnostics.appendLine("")
                
                if (backendPid != null) {
                    val isAlive = try {
                        java.lang.ProcessHandle.of(backendPid).map { it.isAlive }.orElse(false)
                    } catch (e: Exception) {
                        false
                    }
                    diagnostics.appendLine("--- Backend Status ---")
                    diagnostics.appendLine("PID: $backendPid (${if (isAlive) "RUNNING" else "STOPPED"})")
                    diagnostics.appendLine("")
                }
                
                if (healthStatus != null) {
                    diagnostics.appendLine("--- Health Status ---")
                    diagnostics.appendLine("Overall: ${healthStatus.overall}")
                    diagnostics.appendLine("")
                    healthStatus.coreServices.forEach { (name, check) ->
                        diagnostics.appendLine("  $name: ${check.status} - ${check.message}")
                    }
                    diagnostics.appendLine("")
                    healthStatus.reasons.take(5).forEach { reason ->
                        diagnostics.appendLine("  - $reason")
                    }
                    diagnostics.appendLine("")
                }
                
                // Mask sensitive data
                val secretKeys = SecurityUtils.getCommonSecretKeys()
                val maskedDiagnostics = SecurityUtils.maskSensitiveData(diagnostics.toString(), secretKeys)
                
                // Copy to clipboard
                Platform.runLater {
                    val clipboard = Clipboard.getSystemClipboard()
                    val content = ClipboardContent()
                    content.putString(maskedDiagnostics)
                    clipboard.setContent(content)
                    
                    copyDiagnosticsButton.isDisable = false
                    copyDiagnosticsButton.text = "Copy Diagnostics"
                    
                    val alert = Alert(Alert.AlertType.INFORMATION)
                    alert.title = "Diagnostics Copied"
                    alert.headerText = "Diagnostics snapshot copied to clipboard"
                    alert.contentText = "The diagnostics snapshot has been copied to your clipboard.\n\nNote: Sensitive data (passwords, tokens, secrets) has been masked for security."
                    alert.showAndWait()
                }
            } catch (e: Exception) {
                logger.error("Failed to copy diagnostics", e)
                Platform.runLater {
                    copyDiagnosticsButton.isDisable = false
                    copyDiagnosticsButton.text = "Copy Diagnostics"
                    showError("Failed to copy diagnostics", "Could not copy diagnostics to clipboard.\n\nError: ${e.message}\n\nAction: Use 'Collect Diagnostics' to save to file instead.")
                }
            }
        }
    }
    
    private fun collectDiagnostics() {
        diagnosticsButton.isDisable = true
        diagnosticsButton.text = "Collecting..."
        
        val backendPid = processRunner.get()?.getPid() ?: getBackendPidFromFile()
        val healthStatus = healthCheckService?.getCurrentStatus()
        val version = loadVersion()
        
        diagnosticsCollector.collectDiagnostics(
            launcherVersion = version,
            healthStatus = healthStatus,
            backendPid = backendPid,
            onComplete = { diagnosticsFile ->
                Platform.runLater {
                    diagnosticsButton.isDisable = false
                    diagnosticsButton.text = "Collect Diagnostics"
                    
                    // Open file
                    try {
                        val processBuilder = ProcessBuilder("xdg-open", diagnosticsFile.toString())
                        processBuilder.start()
                    } catch (e: Exception) {
                        logger.warn("Failed to open diagnostics file", e)
                    }
                    
                    // Show notification
                    val alert = Alert(Alert.AlertType.INFORMATION)
                    alert.title = "Diagnostics Collected"
                    alert.headerText = "Diagnostics snapshot saved"
                    alert.contentText = "Diagnostics saved to:\n$diagnosticsFile\n\nFile opened automatically."
                    alert.showAndWait()
                }
            },
            onError = { error ->
                Platform.runLater {
                    diagnosticsButton.isDisable = false
                    diagnosticsButton.text = "Collect Diagnostics"
                    
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Diagnostics Error"
                    alert.headerText = "Failed to collect diagnostics"
                    alert.contentText = "Error: $error"
                    alert.showAndWait()
                }
            }
        )
    }
    
    private fun loadVersion(): String {
        val versionFile = Paths.get(System.getProperty("user.home"), ".jarvis", "app", "VERSION")
        val installedVersion = try {
            if (Files.exists(versionFile)) {
                Files.readString(versionFile).trim()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
        
        // Stage 12: Get bundle version from manifest
        val bundleVersion = try {
            val clazz = LauncherApplication::class.java
            val pkg = clazz.getPackage()
            val manifest = clazz.classLoader.getResources("META-INF/MANIFEST.MF")
                .asSequence()
                .firstOrNull()
                ?.openStream()
                ?.bufferedReader()
                ?.readText()
            manifest?.let {
                val versionLine = it.lines().find { line ->
                    line.startsWith("Implementation-Version:")
                }
                versionLine?.substringAfter(":")?.trim()
            } ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        
        // Stage 12: Get release source
        val releaseSource = try {
            if (Files.exists(JarvisPaths.releaseSourceFile)) {
                val source = Files.readString(JarvisPaths.releaseSourceFile).trim()
                if (source == "REPO") {
                    "repo"
                } else {
                    try {
                        Paths.get(source).fileName.toString()
                    } catch (e: Exception) {
                        "unknown"
                    }
                }
            } else {
                "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }
        
        // Stage 12: Format version string
        val versionParts = mutableListOf<String>()
        if (installedVersion != null) {
            versionParts.add("Installed: $installedVersion")
        } else {
            versionParts.add("Installed: unknown")
        }
        
        val bundleVer = try {
            val clazz = LauncherApplication::class.java
            val pkg = clazz.`package`
            pkg?.implementationVersion ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        versionParts.add("Bundle: $bundleVer")
        
        // Check mismatch
        val mismatch = installedVersion != null && bundleVer != "unknown" && installedVersion != bundleVer
        if (mismatch) {
            versionParts.add("⚠️ mismatch")
        }
        
        versionParts.add("Source: $releaseSource")
        
        return versionParts.joinToString(" | ")
    }
    
    // Stage 15: Update status badge color
    private fun updateStatusBadge(newStatus: LauncherStatus) {
        when (newStatus) {
            LauncherStatus.IDLE -> {
                statusBadge.text = "IDLE"
                statusBadge.style = "-fx-background-color: #808080; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12pt; -fx-padding: 5 10 5 10; -fx-background-radius: 5;"
            }
            LauncherStatus.STARTING -> {
                statusBadge.text = "STARTING"
                statusBadge.style = "-fx-background-color: #FFA500; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12pt; -fx-padding: 5 10 5 10; -fx-background-radius: 5;"
            }
            LauncherStatus.READY -> {
                statusBadge.text = "READY"
                statusBadge.style = "-fx-background-color: #00AA00; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12pt; -fx-padding: 5 10 5 10; -fx-background-radius: 5;"
            }
            LauncherStatus.DEGRADED -> {
                statusBadge.text = "DEGRADED"
                statusBadge.style = "-fx-background-color: #FF8C00; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12pt; -fx-padding: 5 10 5 10; -fx-background-radius: 5;"
            }
            LauncherStatus.ERROR -> {
                statusBadge.text = "ERROR"
                statusBadge.style = "-fx-background-color: #CC0000; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12pt; -fx-padding: 5 10 5 10; -fx-background-radius: 5;"
            }
        }
    }
    
    private fun updateStatus(newStatus: LauncherStatus) {
        status.set(newStatus)
        statusLabel.text = newStatus.name
        
        when (newStatus) {
            LauncherStatus.IDLE -> {
                startButton.isDisable = false
                stopButton.isDisable = true
                startDesktopButton.isDisable = true
                statusLabel.style = "-fx-text-fill: gray; -fx-font-weight: bold;"
            }
            LauncherStatus.STARTING -> {
                startButton.isDisable = true
                stopButton.isDisable = false
                startDesktopButton.isDisable = true
                statusLabel.style = "-fx-text-fill: orange; -fx-font-weight: bold;"
            }
            LauncherStatus.READY -> {
                startButton.isDisable = true
                stopButton.isDisable = false
                startDesktopButton.isDisable = false
                statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
            }
            LauncherStatus.DEGRADED -> {
                startButton.isDisable = true
                stopButton.isDisable = false
                startDesktopButton.isDisable = false  // Desktop available in DEGRADED (core works)
                statusLabel.style = "-fx-text-fill: orange; -fx-font-weight: bold;"
            }
            LauncherStatus.ERROR -> {
                startButton.isDisable = false
                stopButton.isDisable = false
                startDesktopButton.isDisable = true
                statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"
            }
        }
    }
    
    private fun appendLog(line: String) {
        Platform.runLater {
            // Keep only last 200 lines in status log
            val currentText = statusLogArea.text
            val lines = if (currentText.isBlank()) emptyList() else currentText.split("\n")
            val newLines = if (lines.size >= 200) {
                lines.takeLast(199) + line
            } else {
                lines + line
            }
            statusLogArea.text = newLines.joinToString("\n")
            statusLogArea.positionCaret(statusLogArea.text.length)
        }
    }
    
    private fun showError(title: String, message: String) {
        val alert = Alert(Alert.AlertType.ERROR)
        alert.title = "Error"
        alert.headerText = title
        alert.contentText = message
        alert.showAndWait()
    }
    
    // Stage 12: Open install log
    private fun openInstallLog() {
        executor.execute {
            try {
                val installLogFile = JarvisPaths.installLog.toFile()
                if (!installLogFile.exists()) {
                    Platform.runLater {
                        val alert = Alert(Alert.AlertType.INFORMATION)
                        alert.title = "Install Log"
                        alert.headerText = "Install log not found"
                        alert.contentText = "Install log file does not exist yet:\n${installLogFile.absolutePath}\n\nIt will be created on first installation."
                        alert.showAndWait()
                    }
                    return@execute
                }
                val process = ProcessBuilder("xdg-open", installLogFile.absolutePath).start()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    // xdg-open may fail in headless session - show friendly message
                    Platform.runLater {
                        val alert = Alert(Alert.AlertType.INFORMATION)
                        alert.title = "Install Log"
                        alert.headerText = "Could not open install log automatically"
                        alert.contentText = "Please open manually:\n${installLogFile.absolutePath}"
                        alert.showAndWait()
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to open install log", e)
                Platform.runLater {
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Error"
                    alert.headerText = "Failed to open install log"
                    alert.contentText = "Error: ${e.message}\n\nFile location: ${JarvisPaths.installLog.toFile().absolutePath}"
                    alert.showAndWait()
                }
            }
        }
    }
    
    // Stage 12: Open release folder
    private fun openReleaseFolder() {
        executor.execute {
            try {
                val releaseSourceFile = JarvisPaths.releaseSourceFile.toFile()
                if (!releaseSourceFile.exists()) {
                    Platform.runLater {
                        val alert = Alert(Alert.AlertType.INFORMATION)
                        alert.title = "Release Folder"
                        alert.headerText = "Release source not found"
                        alert.contentText = "Release source file does not exist:\n${releaseSourceFile.absolutePath}\n\nFalling back to app directory."
                        // Fallback to app directory
                        try {
                            val appDir = JarvisPaths.root.resolve("app").toFile()
                            if (appDir.exists()) {
                                ProcessBuilder("xdg-open", appDir.absolutePath).start()
                            }
                        } catch (e: Exception) {
                            logger.error("Failed to open app directory", e)
                        }
                        alert.showAndWait()
                    }
                    return@execute
                }
                
                val sourcePath = Files.readString(releaseSourceFile.toPath()).trim()
                if (sourcePath == "REPO") {
                    Platform.runLater {
                        val alert = Alert(Alert.AlertType.INFORMATION)
                        alert.title = "Release Folder"
                        alert.headerText = "Installed from repository"
                        alert.contentText = "This installation was done from the repository, not from a release archive.\n\nFalling back to app directory."
                        val appDir = JarvisPaths.root.resolve("app").toFile()
                        if (appDir.exists()) {
                            ProcessBuilder("xdg-open", appDir.absolutePath).start()
                        }
                        alert.showAndWait()
                    }
                    return@execute
                }
                
                val releaseDir = Paths.get(sourcePath).toFile()
                if (!releaseDir.exists() || !releaseDir.isDirectory) {
                    Platform.runLater {
                        val alert = Alert(Alert.AlertType.WARNING)
                        alert.title = "Release Folder"
                        alert.headerText = "Release directory not found"
                        alert.contentText = "Release directory does not exist:\n$sourcePath\n\nFalling back to app directory."
                        val appDir = JarvisPaths.root.resolve("app").toFile()
                        if (appDir.exists()) {
                            ProcessBuilder("xdg-open", appDir.absolutePath).start()
                        }
                        alert.showAndWait()
                    }
                    return@execute
                }
                
                val process = ProcessBuilder("xdg-open", releaseDir.absolutePath).start()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    // xdg-open may fail in headless session - show friendly message
                    Platform.runLater {
                        val alert = Alert(Alert.AlertType.INFORMATION)
                        alert.title = "Release Folder"
                        alert.headerText = "Could not open release folder automatically"
                        alert.contentText = "Please open manually:\n${releaseDir.absolutePath}"
                        alert.showAndWait()
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to open release folder", e)
                Platform.runLater {
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Error"
                    alert.headerText = "Failed to open release folder"
                    alert.contentText = "Error: ${e.message}"
                    alert.showAndWait()
                }
            }
        }
    }
}

fun main() {
    Application.launch(LauncherApplication::class.java)
}

