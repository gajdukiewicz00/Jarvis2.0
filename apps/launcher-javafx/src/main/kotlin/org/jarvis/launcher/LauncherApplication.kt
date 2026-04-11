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
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * JavaFX Application for Jarvis Launcher.
 * Provides GUI for starting/stopping backend and launching the desktop UI.
 */
class LauncherApplication : Application() {
    private val logger = LoggerFactory.getLogger(LauncherApplication::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val autoStart = System.getenv("JARVIS_AUTO_START")?.toBoolean() ?: true
    private val autoBootstrap = System.getenv("JARVIS_AUTO_BOOTSTRAP")?.toBoolean() ?: !JarvisPaths.isLocalRuntime()
    private val autoInstallDeps = System.getenv("JARVIS_AUTO_INSTALL_DEPS")?.toBoolean() ?: true
    private val configManager = LauncherConfig(JarvisPaths.launcherConfig)
    private val initialSettings = configManager.load()
    private val gpuAvailable = detectGpu()
    private var enableLlm = System.getenv("JARVIS_ENABLE_LLM")?.toBoolean()
        ?: initialSettings.enableLlm
    private var enableMemory = System.getenv("JARVIS_ENABLE_MEMORY")?.toBoolean()
        ?: initialSettings.enableMemory
    private var enableGpuSetting = System.getenv("JARVIS_ENABLE_GPU")?.toBoolean()
        ?: initialSettings.enableGpu
    private var enableGpu = enableGpuSetting && gpuAvailable
    @Volatile private var bootstrapKubeconfig: String? = null
    
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
    private lateinit var enableLlmCheckBox: CheckBox
    private lateinit var enableMemoryCheckBox: CheckBox
    private lateinit var enableGpuCheckBox: CheckBox
    private lateinit var fixTlsButton: Button
    private lateinit var resetNamespaceButton: Button
    private lateinit var diskCleanupButton: Button
    private lateinit var acceptanceButton: Button
    private lateinit var gpuSetupButton: Button
    
    // Stage 5: Desktop process tracking
    private var desktopProcess: Process? = null
    private val desktopProcessLock = java.util.concurrent.locks.ReentrantLock()
    
    private val processRunner = AtomicReference<ProcessRunner?>(null)
    private val status = AtomicReference<LauncherStatus>(LauncherStatus.IDLE)
    private var healthCheckService: HealthCheckService? = null
    private var healthCheckTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val healthCheckExecutor = Executors.newScheduledThreadPool(1)
    private var launcherLockChannel: FileChannel? = null
    private var launcherLock: FileLock? = null
    private val startAllInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val backendExpectedRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val requireVoiceGateway = System.getenv("JARVIS_REQUIRE_VOICE_GATEWAY")?.toBoolean() ?: false
    
    // Stage 4: Log Viewer and Diagnostics
    private var logViewer: LogViewer? = null
    private val diagnosticsCollector = DiagnosticsCollector()
    private val bootstrapInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val bootstrapCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
    
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

        if (!acquireLauncherLock()) {
            logger.warn("Another launcher instance is already running. Exiting.")
            Platform.exit()
            return
        }
        
        // Setup UI
        val root = createUI()
        val scene = Scene(root, 800.0, 600.0)
        
        primaryStage.title = "Jarvis 2.0 Launcher"
        primaryStage.scene = scene
        primaryStage.setOnCloseRequest {
            logger.info("Launcher closing...")
            healthCheckTask?.cancel(false)
            healthCheckExecutor.shutdown()
            executor.shutdown()
            diagnosticsCollector.shutdown()
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
            releaseLauncherLock()
            Platform.exit()
        }
        
        primaryStage.show()
        primaryStage.toFront()
        primaryStage.requestFocus()
        
        // Initialize health check service
        val apiUrl = JarvisPaths.getApiGatewayUrl()
        healthCheckService = HealthCheckService(
            apiUrl,
            { bootstrapKubeconfig ?: System.getenv("KUBECONFIG") }
        ) { healthStatus ->
            Platform.runLater {
                updateStatusFromHealth(healthStatus)
            }
        }
        appendLog("Launcher runtime target: ${JarvisPaths.describeRuntimeTarget()}")

        if (autoStart && !JarvisPaths.isLocalRuntime()) {
            appendLog("K8s auto-start: preparing Kubernetes runtime...")
            updateStatus(LauncherStatus.STARTING)
        }

        // Stage 5: Cleanup stale PIDs on startup
        cleanupStalePids()

        // Check if backend is already running
        checkBackendStatus()

        healthCheckService?.updateFlags(
            enableLlm,
            enableMemory,
            voiceRequired = requireVoiceGateway
        )
        
        // Start periodic health checks (every 5 seconds)
        startHealthChecks()
        refreshHealthOnce(autoStartAfterRefresh = autoStart)
    }
    
    private fun startHealthChecks() {
        healthCheckTask = healthCheckExecutor.scheduleAtFixedRate({
            try {
                val pid = processRunner.get()?.getPid() ?: getBackendPidFromFile()
                val healthStatus = healthCheckService?.checkHealth(pid, backendExpectedRunning.get())
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

    private fun isProcessAlive(pid: Long): Boolean {
        return try {
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        } catch (e: Exception) {
            false
        }
    }

    private fun healthIndicatesBackendUsable(
        healthStatus: HealthCheckService.ServiceHealthStatus?
    ): Boolean {
        if (healthStatus == null || healthStatus.coreServices.isEmpty()) {
            return false
        }
        return healthStatus.coreServices.values.all {
            it.status == HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP
        }
    }

    private fun acquireLauncherLock(): Boolean {
        return try {
            val lockFile = JarvisPaths.run.resolve("launcher.lock")
            Files.createDirectories(lockFile.parent)
            val channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            )
            val lock = channel.tryLock()
            if (lock == null) {
                channel.close()
                false
            } else {
                launcherLockChannel = channel
                launcherLock = lock
                true
            }
        } catch (e: Exception) {
            logger.warn("Failed to acquire launcher lock", e)
            true
        }
    }

    private fun releaseLauncherLock() {
        try {
            launcherLock?.release()
        } catch (e: Exception) {
            logger.debug("Failed to release launcher lock", e)
        } finally {
            launcherLock = null
        }
        try {
            launcherLockChannel?.close()
        } catch (e: Exception) {
            logger.debug("Failed to close launcher lock channel", e)
        } finally {
            launcherLockChannel = null
        }
    }

    private fun persistSettings() {
        val existing = try { configManager.load() } catch (_: Exception) { LauncherSettings() }
        configManager.save(
            existing.copy(
                enableLlm = enableLlm,
                enableMemory = enableMemory,
                enableGpu = enableGpuSetting
            )
        )
    }

    private fun syncK8sRunSummary(status: LauncherStatus) {
        if (JarvisPaths.isLocalRuntime()) {
            return
        }

        val summaryStatus = when (status) {
            LauncherStatus.IDLE -> "stopped"
            LauncherStatus.STARTING -> "starting"
            LauncherStatus.READY -> "ready"
            LauncherStatus.DEGRADED -> "degraded"
            LauncherStatus.ERROR -> "error"
        }

        JarvisPaths.writeRuntimeRunSummary(
            status = summaryStatus,
            apiUrl = JarvisPaths.getApiGatewayUrl(),
            voiceUrl = "wss://voice.jarvis.local",
            runtimeMode = "k8s",
            grafanaUrl = JarvisPaths.getGrafanaUrl()
        )
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
                    .filter {
                        it.status != HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP &&
                            !it.isDisabled
                    }
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
            if (newLauncherStatus == LauncherStatus.STARTING ||
                newLauncherStatus == LauncherStatus.READY ||
                newLauncherStatus == LauncherStatus.DEGRADED
            ) {
                syncK8sRunSummary(newLauncherStatus)
            }
            updateStatus(newLauncherStatus)
            updateStatusBadge(newLauncherStatus)  // Stage 15: Update badge color
            statusLabel.text = statusText
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
                    apiGateway.message.contains("TLS trust missing") -> {
                        messages.add("TLS trust missing — Launcher cannot verify HTTPS")
                        messages.add("Action: Click 'Fix TLS' to install CA and update /etc/hosts")
                    }
                    apiGateway.message.contains("SSL error") -> {
                        messages.add("TLS certificate issue — check CA trust store")
                        messages.add("Action: Click 'Fix TLS' to install CA")
                    }
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
        startAllButton.tooltip = Tooltip("Start backend and wait for READY, then start the desktop UI")
        stopAllButton.tooltip = Tooltip("Stop desktop UI and backend")
        startDesktopButton.tooltip = Tooltip("Start the desktop UI (prefers unified shell; requires backend READY)")
        
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

        // Feature toggles
        val featureBox = HBox(12.0)
        featureBox.alignment = Pos.CENTER_LEFT
        enableLlmCheckBox = CheckBox("Enable LLM")
        enableLlmCheckBox.isSelected = enableLlm
        enableLlmCheckBox.tooltip = Tooltip("Optional: GPU-backed LLM stack (does not block core)")
        enableLlmCheckBox.setOnAction {
            enableLlm = enableLlmCheckBox.isSelected
            persistSettings()
            healthCheckService?.updateFlags(enableLlm, enableMemory, voiceRequired = requireVoiceGateway)
        }

        enableMemoryCheckBox = CheckBox("Enable Memory")
        enableMemoryCheckBox.isSelected = enableMemory
        enableMemoryCheckBox.tooltip = Tooltip("Optional: memory + pgvector stack (does not block core)")
        enableMemoryCheckBox.setOnAction {
            enableMemory = enableMemoryCheckBox.isSelected
            persistSettings()
            healthCheckService?.updateFlags(enableLlm, enableMemory, voiceRequired = requireVoiceGateway)
        }

        enableGpuCheckBox = CheckBox("Enable GPU")
        enableGpuCheckBox.isSelected = enableGpuSetting
        enableGpuCheckBox.isDisable = !gpuAvailable
        enableGpuCheckBox.tooltip = Tooltip(if (gpuAvailable) "Use GPU for LLM workloads" else "GPU not detected on host")
        enableGpuCheckBox.setOnAction {
            enableGpuSetting = enableGpuCheckBox.isSelected
            enableGpu = enableGpuSetting && gpuAvailable
            persistSettings()
        }

        featureBox.children.addAll(enableLlmCheckBox, enableMemoryCheckBox, enableGpuCheckBox)

        // Action buttons
        val actionBox = HBox(10.0)
        actionBox.alignment = Pos.CENTER_LEFT
        fixTlsButton = Button("Fix TLS")
        fixTlsButton.tooltip = Tooltip("Generate certs, install CA, and update /etc/hosts")
        fixTlsButton.setOnAction { fixTls() }

        resetNamespaceButton = Button("Reset Jarvis")
        resetNamespaceButton.tooltip = Tooltip("Clean jarvis namespace (preserve data by default)")
        resetNamespaceButton.setOnAction { resetNamespace() }

        diskCleanupButton = Button("Disk Cleanup")
        diskCleanupButton.tooltip = Tooltip("Safe cleanup to reduce DiskPressure")
        diskCleanupButton.setOnAction { runDiskCleanup() }

        gpuSetupButton = Button("Enable GPU")
        gpuSetupButton.tooltip = Tooltip("Install NVIDIA runtime + device plugin for k3s")
        gpuSetupButton.setOnAction { runGpuSetup() }

        acceptanceButton = Button("Run Acceptance")
        acceptanceButton.tooltip = Tooltip("Run verify + acceptance via port-forward")
        acceptanceButton.setOnAction { runAcceptance() }

        actionBox.children.addAll(fixTlsButton, resetNamespaceButton, diskCleanupButton, gpuSetupButton, acceptanceButton)
        
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
        
        controlContent.children.addAll(header, statusBox, buttonBox, featureBox, actionBox, statusLogScroll)
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
                        backendExpectedRunning.set(true)
                        Platform.runLater {
                            updateStatus(LauncherStatus.STARTING)
                            appendLog("Bootstrap process already running (PID: $pid); waiting for health checks")
                            refreshHealthOnce()
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
    
    private fun validateK8sPreconditions(): Boolean {
        if (JarvisPaths.isLocalRuntime()) {
            return true
        }
        val kubeconfig = bootstrapKubeconfig
            ?: System.getenv("KUBECONFIG")?.takeIf { Files.exists(Paths.get(it)) }?.toString()
            ?: Paths.get(System.getProperty("user.home"), ".jarvis", "kubeconfig")
                .takeIf { Files.exists(it) }?.toString()
        if (kubeconfig == null) {
            showError(
                "Kubernetes not ready",
                "No kubeconfig found. Bootstrap should have set one up.\n\n" +
                    "This likely means k3s is not installed or the bootstrap was skipped.\n\n" +
                    "Action: Check the Control tab log for bootstrap errors.\n" +
                    "Manual fix: sudo cp /etc/rancher/k3s/k3s.yaml ~/.jarvis/kubeconfig && sudo chown \$(whoami) ~/.jarvis/kubeconfig"
            )
            return false
        }

        val hostsOk = try {
            val hostsContent = Files.readAllLines(Paths.get("/etc/hosts"))
            hostsContent.any { it.contains("api.jarvis.local") } &&
                hostsContent.any { it.contains("voice.jarvis.local") } &&
                hostsContent.any { it.contains("grafana.jarvis.local") }
        } catch (_: Exception) { false }
        if (!hostsOk) {
            appendLog("WARNING: /etc/hosts missing api.jarvis.local / voice.jarvis.local / grafana.jarvis.local — TLS/ingress may fail")
        }
        return true
    }

    private fun startBackend() {
        if (!validateK8sPreconditions()) {
            updateStatus(LauncherStatus.ERROR)
            appendLog("K8s precondition check failed — see error dialog")
            return
        }

        // Check if already running
        val existingRunner = processRunner.get()
        if (existingRunner != null && existingRunner.isRunning()) {
            backendExpectedRunning.set(true)
            appendLog("Bootstrap process already running (PID: ${existingRunner.getPid()}); waiting for health checks")
            updateStatus(LauncherStatus.STARTING)
            refreshHealthOnce()
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
                    backendExpectedRunning.set(true)
                    appendLog("Bootstrap process already running (detected via PID file: $pid); waiting for health checks")
                    updateStatus(LauncherStatus.STARTING)
                    refreshHealthOnce()
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
        appendLog("Launch target: ${JarvisPaths.describeRuntimeTarget()}")

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
            backendExpectedRunning.set(true)
            val envVars = mutableMapOf(
                "ENABLE_LLM" to enableLlm.toString(),
                "ENABLE_MEMORY" to enableMemory.toString(),
                "ENABLE_GPU" to enableGpu.toString()
            )
            val kubeConfig = bootstrapKubeconfig ?: System.getenv("KUBECONFIG")
            if (!kubeConfig.isNullOrBlank()) {
                envVars["KUBECONFIG"] = kubeConfig
            }
            BackendLaunchLogSupport.writeBackendLaunchPreamble(launchScript, envVars)
            
            val future = newRunner.start(
                scriptPath = launchScript,
                envVars = envVars,
                workingDir = launchScript.parent
            )
            
            future.whenComplete { exitCode, throwable ->
                Platform.runLater {
                    if (throwable != null) {
                        logger.error("Backend start failed", throwable)
                        backendExpectedRunning.set(false)
                        updateStatus(LauncherStatus.ERROR)
                        appendLog("ERROR: ${throwable.message}")
                        BackendLaunchLogSupport.appendBackendLaunchLogLine("Bootstrap failed: ${throwable.message ?: "unknown error"}")
                        showError(
                            "Backend start failed",
                            "${throwable.message ?: "Unknown error"}\n\n" +
                            "Check logs: ${JarvisPaths.backendLaunchLog}\n" +
                            "Click 'Open Logs' to view details."
                        )
                    } else {
                        if (exitCode == 0) {
                            backendExpectedRunning.set(true)
                            updateStatus(LauncherStatus.STARTING)
                            appendLog("Backend bootstrap finished; waiting for service health before READY")
                            BackendLaunchLogSupport.appendBackendLaunchLogLine("Bootstrap exit code: 0")
                            refreshHealthOnce()
                        } else {
                            backendExpectedRunning.set(false)
                            updateStatus(LauncherStatus.ERROR)
                            appendLog("Backend exited with code: $exitCode")
                            BackendLaunchLogSupport.appendBackendLaunchLogLine("Bootstrap exit code: $exitCode")
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
                        backendExpectedRunning.set(false)
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
                            backendExpectedRunning.set(false)
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
            appendLog("Desktop UI already running")
            showError("Desktop already running", "Desktop UI is already started. Check if its window is open.")
            return
        }
        
        val desktopJar = JarvisPaths.getDesktopJar()
        
        if (!Files.exists(desktopJar)) {
            showError(
                "Desktop JAR not found",
                "Cannot find desktop launch artifact: $desktopJar\n\n" +
                    "Build desktop-app-javafx first:\n" +
                    "mvn -pl apps/desktop-app-javafx -am -DskipTests clean package"
            )
            return
        }
        
        appendLog("Starting desktop UI...")
        appendLog("Desktop launch artifact: ${desktopJar.fileName}")
        
        executor.execute {
            try {
                val command = mutableListOf(
                    "java"
                )
                val trustStore = System.getenv("JARVIS_JAVA_TRUSTSTORE")
                val trustStorePass = System.getenv("JARVIS_JAVA_TRUSTSTORE_PASSWORD") ?: "changeit"
                if (!trustStore.isNullOrBlank() && Files.exists(Paths.get(trustStore))) {
                    command.add("-Djavax.net.ssl.trustStore=$trustStore")
                    command.add("-Djavax.net.ssl.trustStorePassword=$trustStorePass")
                }
                command.add("-Dlogback.configurationFile=${JarvisPaths.logs.resolve("logback-desktop.xml")}")
                command.add("-jar")
                command.add(desktopJar.toString())

                val processBuilder = ProcessBuilder(command)
                    .directory(desktopJar.parent.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(JarvisPaths.desktopLog.toFile()))
                    .redirectError(ProcessBuilder.Redirect.appendTo(JarvisPaths.desktopLog.toFile()))
                val env = processBuilder.environment()
                val apiBaseUrl = JarvisPaths.getApiGatewayUrl()
                env["JARVIS_API_BASE_URL"] = apiBaseUrl
                env["JARVIS_RUNTIME_MODE"] = JarvisPaths.getRuntimeMode()
                env["JARVIS_USE_TLS"] = if (apiBaseUrl.startsWith("https://")) "true" else "false"
                appendLog("Desktop endpoint target: $apiBaseUrl")
                val process = processBuilder.start()
                
                // Stage 5: Save desktop PID
                desktopProcessLock.lock()
                try {
                    desktopProcess = process
                    Files.writeString(JarvisPaths.desktopPid, process.pid().toString())
                } finally {
                    desktopProcessLock.unlock()
                }
                
                appendLog("Desktop UI started (PID: ${process.pid()})")
            } catch (e: Exception) {
                logger.error("Failed to start desktop UI", e)
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
     * Stage 5: Stop desktop UI.
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
                        appendLog("Stopping desktop UI (PID: $pid)...")
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
        if (!startAllInProgress.compareAndSet(false, true)) {
            appendLog("Start All already in progress")
            return
        }

        var k8sRevalidationStarted = false

        // Idempotency: check if already running
        val currentStatus = status.get()
        if (currentStatus == LauncherStatus.READY || currentStatus == LauncherStatus.DEGRADED) {
            if (!JarvisPaths.isLocalRuntime()) {
                appendLog("Backend already UP, revalidating Kubernetes runtime + observability...")
                startBackend()
                k8sRevalidationStarted = true
            } else {
                if (!isDesktopRunning()) {
                    appendLog("Backend already READY, starting desktop...")
                    startDesktop()
                } else {
                    appendLog("Desktop already running")
                }
                startAllInProgress.set(false)
                return
            }
        }

        val currentHealth = healthCheckService?.getCurrentStatus()
        if (!k8sRevalidationStarted && healthIndicatesBackendUsable(currentHealth)) {
            backendExpectedRunning.set(true)
            if (!JarvisPaths.isLocalRuntime()) {
                appendLog("Backend core services already UP, revalidating Kubernetes runtime + observability...")
                startBackend()
                k8sRevalidationStarted = true
            } else {
                if (!isDesktopRunning()) {
                    appendLog("Backend core services already UP, starting desktop...")
                    startDesktop()
                } else {
                    appendLog("Desktop already running")
                }
                startAllInProgress.set(false)
                return
            }
        }
        
        if (!k8sRevalidationStarted && currentStatus == LauncherStatus.STARTING) {
            val runnerActive = processRunner.get()?.isRunning() == true
            val backendPid = getBackendPidFromFile()
            val backendProcessAlive = backendPid != null && isProcessAlive(backendPid)
            if (runnerActive || backendProcessAlive) {
                appendLog("Backend is already starting, please wait...")
                startAllInProgress.set(false)
                return
            }
            appendLog("Status is STARTING, but no backend bootstrap process is running. Retrying startup...")
        }
        
        if (!k8sRevalidationStarted && (!autoBootstrap || bootstrapCompleted.get())) {
            appendLog("Start All: Starting backend...")
            startBackend()
        } else if (!k8sRevalidationStarted) {
            if (!bootstrapInProgress.compareAndSet(false, true)) {
                appendLog("Bootstrap already running, please wait...")
                return
            }
            executor.execute {
                val ok = runBootstrap()
                bootstrapInProgress.set(false)
                if (ok) {
                    bootstrapCompleted.set(true)
                    Platform.runLater {
                        appendLog("Bootstrap completed, starting backend...")
                        startBackend()
                    }
                } else {
                    Platform.runLater {
                        appendLog("ERROR: Bootstrap failed — cannot start Kubernetes runtime")
                        updateStatus(LauncherStatus.ERROR)
                    }
                    startAllInProgress.set(false)
                }
            }
        }
        
        // Wait for READY/DEGRADED with timeout
        executor.execute {
            val startTime = System.currentTimeMillis()
            val timeoutMs = 1_200_000L  // 20 minutes
            
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val currentStatus = status.get()
                val runner = processRunner.get()
                val backendFinished = runner == null || !runner.isRunning()
                if ((currentStatus == LauncherStatus.READY || currentStatus == LauncherStatus.DEGRADED) && backendFinished) {
                    Platform.runLater {
                        appendLog("Backend is READY, starting desktop...")
                        startDesktop()
                    }
                    startAllInProgress.set(false)
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
                    startAllInProgress.set(false)
                    return@execute
                }
                
                Thread.sleep(2000)  // Check every 2 seconds
            }
            
            // Timeout
            Platform.runLater {
                appendLog("ERROR: Timeout waiting for backend READY (20m)")
                showError(
                    "Timeout waiting for backend",
                    "Backend did not become READY within 20 minutes.\n\n" +
                    "Action: Click 'Collect Diagnostics' to see details.\n" +
                    "Check logs in the Logs tab for errors.\n" +
                    "You can try 'Start Backend' separately to debug."
                )
            }
            startAllInProgress.set(false)
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

    private fun runBootstrap(): Boolean {
        appendLog("Bootstrap: checking dependencies...")
        if (!ensureDependencies()) {
            return false
        }
        if (!checkDiskSpaceAndOfferCleanup()) {
            return false
        }
        appendLog("Bootstrap: ensuring secrets...")
        if (!ensureSecrets()) {
            return false
        }
        appendLog("Bootstrap: ensuring TLS certificates...")
        if (!ensureTls()) {
            return false
        }
        appendLog("Bootstrap: system setup (CA trust + /etc/hosts)...")
        if (!ensureSystemSetup()) {
            return false
        }
        return true
    }

    private fun checkDiskSpaceAndOfferCleanup(): Boolean {
        val freeGb = getRootFreeGb() ?: return true
        if (freeGb >= 8) {
            return true
        }
        val proceed = confirmDialog(
            title = "Low disk space",
            header = "Low disk space on /",
            content = "Only ${freeGb}GB free on /. This can cause pod evictions.\n\nRun Disk Cleanup now?",
            okText = "Run Cleanup"
        )
        if (!proceed) {
            appendLog("Disk cleanup skipped by user")
            return true
        }
        return runDiskCleanupSync()
    }

    private fun getRootFreeGb(): Long? {
        return try {
            val process = ProcessBuilder("bash", "-lc", "df -Pk / | awk 'NR==2 {print $4}'")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            val kb = process.inputStream.bufferedReader().readText().trim().toLongOrNull() ?: return null
            kb / (1024 * 1024)
        } catch (e: Exception) {
            null
        }
    }

    private fun confirmDialog(title: String, header: String, content: String, okText: String = "OK"): Boolean {
        val future = java.util.concurrent.CompletableFuture<Boolean>()
        Platform.runLater {
            val alert = Alert(Alert.AlertType.CONFIRMATION)
            alert.title = title
            alert.headerText = header
            alert.contentText = content
            val okButton = ButtonType(okText, ButtonBar.ButtonData.OK_DONE)
            alert.buttonTypes.setAll(okButton, ButtonType.CANCEL)
            val result = alert.showAndWait()
            future.complete(result.orElse(ButtonType.CANCEL) == okButton)
        }
        return future.get(2, TimeUnit.MINUTES)
    }

    private fun ensureDependencies(): Boolean {
        val requiresDocker = JarvisPaths.isLocalRuntime()
        if (enableLlm && enableGpuSetting && !gpuAvailable) {
            appendLog("GPU not detected on host; disabling LLM for this run.")
            enableLlm = false
            Platform.runLater {
                enableLlmCheckBox.isSelected = false
            }
            persistSettings()
        }
        val missingPackages = mutableListOf<String>()
        if (!commandExists("java")) missingPackages.add("openjdk-21-jre")
        if (!commandExists("mvn")) missingPackages.add("maven")
        if (!commandExists("kubectl")) missingPackages.add("kubernetes-cli")
        if (requiresDocker && !commandExists("docker")) missingPackages.add("docker.io")
        if (!commandExists("curl")) missingPackages.add("curl")
        if (!commandExists("openssl")) missingPackages.add("openssl")

        if (missingPackages.isNotEmpty()) {
            if (!autoInstallDeps) {
                showError("Missing dependencies", "Install required packages: ${missingPackages.joinToString(", ")}")
                return false
            }
            if (!commandExists("pkexec") || !commandExists("apt-get")) {
                showError("Missing dependencies", "Cannot auto-install; pkexec/apt-get not found.")
                return false
            }
            appendLog("Installing packages: ${missingPackages.joinToString(", ")}")
            val cmd = listOf(
                "pkexec", "/usr/bin/env",
                "DEBIAN_FRONTEND=noninteractive",
                "bash", "-lc",
                "apt-get update && apt-get install -y ${missingPackages.joinToString(" ")}"
            )
            if (runCommand(cmd, JarvisPaths.getProjectRoot()) != 0) {
                showError("Dependency install failed", "Check logs: ${JarvisPaths.launcherLog}")
                return false
            }
        }

        if (requiresDocker && !ensureDockerAccess()) {
            return false
        }

        if (!requiresDocker) {
            appendLog("K8s runtime detected; skipping Docker dependency check.")
        }

        if (!commandExists("k3s")) {
            if (!autoInstallDeps) {
                showError("k3s missing", "Install k3s before starting Jarvis.")
                return false
            }
            if (!commandExists("pkexec") || !commandExists("curl")) {
                showError("k3s missing", "Cannot auto-install k3s (pkexec/curl not found).")
                return false
            }
            appendLog("Installing k3s...")
            val cmd = listOf("pkexec", "/usr/bin/env", "bash", "-lc", "curl -sfL https://get.k3s.io | sh -")
            if (runCommand(cmd, JarvisPaths.getProjectRoot()) != 0) {
                showError("k3s install failed", "Check logs: ${JarvisPaths.launcherLog}")
                return false
            }
        }

        if (!ensureK3sRunning()) {
            return false
        }

        if (!ensureKubeconfig()) {
            return false
        }

        return true
    }

    private fun ensureDockerAccess(): Boolean {
        if (!commandExists("docker")) {
            showError("Docker missing", "Install Docker before starting Jarvis.")
            return false
        }
        if (runCommand(listOf("docker", "info"), JarvisPaths.getProjectRoot(), silent = true) == 0) {
            return true
        }
        val dockerServiceActive = commandExists("systemctl") &&
            runCommand(listOf("systemctl", "is-active", "--quiet", "docker"), JarvisPaths.getProjectRoot(), silent = true) == 0
        if (!dockerServiceActive && commandExists("pkexec") && commandExists("systemctl")) {
            appendLog("Starting docker service...")
            val cmd = listOf("pkexec", "/usr/bin/env", "systemctl", "start", "docker")
            if (runCommand(cmd, JarvisPaths.getProjectRoot()) != 0) {
                showError("Docker not running", "Failed to start docker service.")
                return false
            }
        }
        if (runCommand(listOf("docker", "info"), JarvisPaths.getProjectRoot(), silent = true) == 0) {
            return true
        }
        val user = System.getProperty("user.name")
        val inGroup = runCommand(
            listOf("bash", "-lc", "id -nG \"$user\" | grep -qw docker"),
            JarvisPaths.getProjectRoot(),
            silent = true
        ) == 0
        if (!inGroup) {
            if (!commandExists("pkexec")) {
                showError("Docker permissions", "Add user to docker group and restart session.")
                return false
            }
            appendLog("Adding user to docker group...")
            val cmd = listOf("pkexec", "/usr/bin/env", "usermod", "-aG", "docker", user)
            if (runCommand(cmd, JarvisPaths.getProjectRoot()) != 0) {
                showError("Docker permissions", "Failed to update docker group.")
                return false
            }
            showError(
                "Docker permissions updated",
                "User added to docker group. Please log out and back in, then start Jarvis again."
            )
            return false
        }
        showError("Docker not usable", "Docker is installed but not accessible. Check service and permissions.")
        return false
    }

    private fun ensureK3sRunning(): Boolean {
        if (!commandExists("systemctl")) {
            return true
        }
        val isActive = runCommand(
            listOf("systemctl", "is-active", "--quiet", "k3s"),
            JarvisPaths.getProjectRoot(),
            silent = true
        ) == 0
        if (isActive) {
            return true
        }
        if (!commandExists("pkexec")) {
            showError("k3s not running", "pkexec not found; cannot start k3s.")
            return false
        }
        appendLog("Starting k3s service...")
        val cmd = listOf("pkexec", "/usr/bin/env", "systemctl", "start", "k3s")
        if (runCommand(cmd, JarvisPaths.getProjectRoot()) != 0) {
            showError("k3s not running", "Failed to start k3s service.")
            return false
        }
        return true
    }

    private fun ensureKubeconfig(): Boolean {
        val envConfig = System.getenv("KUBECONFIG")
        if (!envConfig.isNullOrBlank()) {
            val envPath = Paths.get(envConfig)
            if (Files.exists(envPath) && isK3sConfig(envPath)) {
                bootstrapKubeconfig = envConfig
                return true
            }
            appendLog("Ignoring KUBECONFIG (non-k3s), using k3s config")
        }
        val userHome = System.getProperty("user.home")
        val jarvisConfig = Paths.get(userHome, ".jarvis", "kubeconfig")
        if (Files.exists(jarvisConfig)) {
            bootstrapKubeconfig = jarvisConfig.toString()
            return true
        }
        val systemConfig = Paths.get("/etc/rancher/k3s/k3s.yaml")
        if (!Files.exists(systemConfig)) {
            showError(
                "Kubeconfig not found",
                "No kubeconfig found at:\n" +
                    "  - ${jarvisConfig}\n" +
                    "  - ${systemConfig}\n\n" +
                    "Kubernetes mode requires a valid kubeconfig.\n\n" +
                    "To fix:\n" +
                    "  1. Install k3s: curl -sfL https://get.k3s.io | sh -\n" +
                    "  2. Copy config: sudo cp /etc/rancher/k3s/k3s.yaml ~/.jarvis/kubeconfig && sudo chown \$(whoami) ~/.jarvis/kubeconfig\n\n" +
                    "Or set JARVIS_RUNTIME_MODE=local for local development mode."
            )
            return false
        }
        if (!commandExists("pkexec")) {
            showError("Kubeconfig missing", "pkexec not found; cannot copy k3s kubeconfig.")
            return false
        }
        appendLog("Copying k3s kubeconfig to ${jarvisConfig}...")
        val user = System.getProperty("user.name")
        val cmd = listOf(
            "pkexec", "/usr/bin/env",
            "HOME=$userHome",
            "USER=$user",
            "bash", "-lc",
            "mkdir -p \"${'$'}HOME/.jarvis\" && " +
                "cp /etc/rancher/k3s/k3s.yaml \"${'$'}HOME/.jarvis/kubeconfig\" && " +
                "chown \"${'$'}USER:${'$'}USER\" \"${'$'}HOME/.jarvis/kubeconfig\" && " +
                "chmod 600 \"${'$'}HOME/.jarvis/kubeconfig\""
        )
        if (runCommand(cmd, JarvisPaths.getProjectRoot()) != 0) {
            showError("Kubeconfig setup failed", "Check logs: ${JarvisPaths.launcherLog}")
            return false
        }
        bootstrapKubeconfig = jarvisConfig.toString()
        return true
    }

    private fun isK3sConfig(path: java.nio.file.Path): Boolean {
        val normalized = path.toString()
        if (normalized.contains("/k3s/") || normalized.endsWith("/k3s.yaml")) {
            return true
        }
        return try {
            val content = Files.readString(path)
            if (content.contains("k3s")) {
                return true
            }
            Regex("server:\\s*(\\S+)").find(content)?.groupValues?.get(1) == "https://127.0.0.1:6443"
        } catch (e: Exception) {
            false
        }
    }

    private fun ensureSecrets(): Boolean {
        val secretsFile = Paths.get(System.getProperty("user.home"), ".jarvis", "secrets", "secrets.env")
        if (Files.exists(secretsFile)) {
            return true
        }
        val script = JarvisPaths.getProjectRoot().resolve("scripts/product/jarvis-secrets-apply.sh")
        if (!Files.exists(script)) {
            showError("Secrets script missing", "Cannot find: $script")
            return false
        }
        appendLog("Generating local secrets...")
        val cmd = listOf(script.toString(), "--init", "--no-apply")
        if (runCommand(cmd, JarvisPaths.getProjectRoot()) != 0) {
            showError("Secrets init failed", "Check logs: ${JarvisPaths.launcherLog}")
            return false
        }
        Platform.runLater {
            showInfo(
                "Secrets created",
                "Local secrets file created at:\n$secretsFile\n\nYou can edit it later if needed."
            )
        }
        return true
    }

    private fun ensureTls(): Boolean {
        val tlsDir = Paths.get(System.getProperty("user.home"), ".jarvis", "tls")
        val caCert = tlsDir.resolve("jarvis-ca.crt")
        val serverCert = tlsDir.resolve("jarvis.crt")
        if (Files.exists(caCert) && Files.exists(serverCert)) {
            return true
        }
        val script = JarvisPaths.getProjectRoot().resolve("scripts/product/jarvis-generate-certs.sh")
        if (!Files.exists(script)) {
            showError("TLS script missing", "Cannot find: $script")
            return false
        }
        appendLog("Generating TLS certificates...")
        val cmd = listOf(script.toString())
        if (runCommand(cmd, JarvisPaths.getProjectRoot()) != 0) {
            showError("TLS generation failed", "Check logs: ${JarvisPaths.launcherLog}")
            return false
        }
        return true
    }

    private fun ensureSystemSetup(): Boolean {
        val caInstalled = Files.exists(Paths.get("/usr/local/share/ca-certificates/jarvis-ca.crt"))
        val hostsFile = Paths.get("/etc/hosts")
        val hostsOk = try {
            Files.readAllLines(hostsFile).any { it.contains("api.jarvis.local") } &&
                Files.readAllLines(hostsFile).any { it.contains("voice.jarvis.local") } &&
                Files.readAllLines(hostsFile).any { it.contains("grafana.jarvis.local") }
        } catch (e: Exception) {
            false
        }
        if (caInstalled && hostsOk) {
            return true
        }
        if (!commandExists("pkexec")) {
            showError("System setup requires admin", "pkexec not found; cannot install CA/hosts.")
            return false
        }
        val script = JarvisPaths.getProjectRoot().resolve("scripts/product/jarvis-system-setup.sh")
        if (!Files.exists(script)) {
            showError("System setup script missing", "Cannot find: $script")
            return false
        }
        appendLog("Applying CA trust and /etc/hosts...")
        val env = mutableMapOf(
            "JARVIS_HOME" to Paths.get(System.getProperty("user.home"), ".jarvis").toString()
        )
        val kubeConfig = System.getenv("KUBECONFIG") ?: bootstrapKubeconfig
        if (!kubeConfig.isNullOrBlank()) {
            env["KUBECONFIG"] = kubeConfig
        }
        val cmd = buildPrivilegedCommand(script.toString(), env)
        if (runCommand(cmd, JarvisPaths.getProjectRoot()) != 0) {
            showError("System setup failed", "Check logs: ${JarvisPaths.launcherLog}")
            return false
        }
        return true
    }

    private fun defaultPrivilegedEnv(): MutableMap<String, String> {
        val env = mutableMapOf<String, String>()
        env["JARVIS_HOME"] = Paths.get(System.getProperty("user.home"), ".jarvis").toString()
        val kubeConfig = bootstrapKubeconfig ?: System.getenv("KUBECONFIG")
        if (!kubeConfig.isNullOrBlank()) {
            env["KUBECONFIG"] = kubeConfig
        }
        return env
    }

    private fun runPrivilegedScript(script: Path, env: Map<String, String> = emptyMap(), args: List<String> = emptyList()): Int {
        if (!Files.exists(script)) {
            showError("Script not found", "Missing: $script")
            return -1
        }
        if (!commandExists("pkexec")) {
            showError("Admin privileges required", "pkexec not found; cannot run privileged step.")
            return -1
        }
        val cmd = buildPrivilegedCommand(script.toString(), env, args)
        return runCommand(cmd, JarvisPaths.getProjectRoot())
    }

    private fun runDiskCleanupSync(): Boolean {
        val script = JarvisPaths.getProjectRoot().resolve("scripts/product/jarvis-disk-cleanup.sh")
        appendLog("Running disk cleanup...")
        val exitCode = runPrivilegedScript(script, defaultPrivilegedEnv())
        if (exitCode == 0) {
            appendLog("Disk cleanup completed")
            return true
        }
        showError("Disk cleanup failed", "Check logs: ${JarvisPaths.launcherLog}")
        return false
    }

    private fun fixTls() {
        fixTlsButton.isDisable = true
        executor.execute {
            appendLog("Fix TLS: generating certs, installing CA, updating /etc/hosts...")
            val script = JarvisPaths.getProjectRoot().resolve("scripts/product/jarvis-fix-tls.sh")
            val exitCode = runPrivilegedScript(script, defaultPrivilegedEnv())
            Platform.runLater {
                fixTlsButton.isDisable = false
                if (exitCode == 0) {
                    appendLog("TLS setup completed")
                    val truststore = Paths.get(System.getProperty("user.home"), ".jarvis", "tls", "jarvis-cacerts.jks")
                    if (Files.exists(truststore)) {
                        System.setProperty("javax.net.ssl.trustStore", truststore.toString())
                        System.setProperty("javax.net.ssl.trustStorePassword", System.getenv("JARVIS_JAVA_TRUSTSTORE_PASSWORD") ?: "changeit")
                    }
                    val pid = processRunner.get()?.getPid() ?: getBackendPidFromFile()
                    val healthStatus = healthCheckService?.checkHealth(pid, backendExpectedRunning.get())
                    if (healthStatus != null) {
                        updateStatusFromHealth(healthStatus)
                        val apiCheck = healthStatus.coreServices["api-gateway"]
                        if (apiCheck?.message?.contains("TLS trust missing", ignoreCase = true) == true) {
                            showError("TLS trust missing", "CA trust is still missing. Please retry Fix TLS.")
                        }
                    } else {
                        refreshHealthOnce()
                    }
                } else {
                    showError("TLS setup failed", "Check logs: ${JarvisPaths.launcherLog}")
                }
            }
        }
    }

    private fun resetNamespace() {
        val wipe = confirmResetDialog()
        if (wipe == null) {
            appendLog("Reset canceled")
            return
        }
        resetNamespaceButton.isDisable = true
        executor.execute {
            val script = JarvisPaths.getProjectRoot().resolve("scripts/product/jarvis-reset-namespace.sh")
            val args = if (wipe) listOf("--wipe-data") else emptyList()
            appendLog("Resetting jarvis namespace (${if (wipe) "wipe data" else "preserve data"})...")
            val exitCode = runPrivilegedScript(script, defaultPrivilegedEnv(), args)
            Platform.runLater {
                resetNamespaceButton.isDisable = false
                if (exitCode == 0) {
                    appendLog("Namespace reset complete")
                    refreshHealthOnce()
                } else {
                    showError("Namespace reset failed", "Check logs: ${JarvisPaths.launcherLog}")
                }
            }
        }
    }

    private fun runDiskCleanup() {
        diskCleanupButton.isDisable = true
        executor.execute {
            val ok = runDiskCleanupSync()
            Platform.runLater {
                diskCleanupButton.isDisable = false
                if (ok) {
                    refreshHealthOnce()
                }
            }
        }
    }

    private fun runGpuSetup() {
        gpuSetupButton.isDisable = true
        executor.execute {
            appendLog("Running GPU setup...")
            val script = JarvisPaths.getProjectRoot().resolve("scripts/product/jarvis-gpu-setup.sh")
            val exitCode = runPrivilegedScript(script, defaultPrivilegedEnv())
            Platform.runLater {
                gpuSetupButton.isDisable = false
                if (exitCode == 0) {
                    appendLog("GPU setup completed")
                    enableGpuSetting = true
                    enableGpu = true
                    enableGpuCheckBox.isSelected = true
                    persistSettings()
                } else {
                    showError("GPU setup failed", "Check logs: ${JarvisPaths.launcherLog}")
                }
            }
        }
    }

    private fun runAcceptance() {
        acceptanceButton.isDisable = true
        executor.execute {
            appendLog("Running acceptance checks...")
            val script = JarvisPaths.getProjectRoot().resolve("scripts/product/jarvis-run-acceptance.sh")
            val exitCode = if (Files.exists(script)) {
                runCommand(listOf(script.toString()), JarvisPaths.getProjectRoot())
            } else {
                -1
            }
            Platform.runLater {
                acceptanceButton.isDisable = false
                if (exitCode == 0) {
                    appendLog("Acceptance completed")
                    val report = findLatestAcceptanceReport()
                    if (report != null) {
                        showInfo("Acceptance report", "Saved to:\n$report")
                    }
                } else {
                    showError(
                        "Acceptance failed",
                        if (Files.exists(script)) "Check logs: ${JarvisPaths.launcherLog}" else "Missing script: $script"
                    )
                }
            }
        }
    }

    private fun refreshHealthOnce(autoStartAfterRefresh: Boolean = false) {
        executor.execute {
            val pid = processRunner.get()?.getPid() ?: getBackendPidFromFile()
            val healthStatus = healthCheckService?.checkHealth(pid, backendExpectedRunning.get())
            Platform.runLater {
                if (healthStatus != null) {
                    updateStatusFromHealth(healthStatus)
                }
                if (autoStartAfterRefresh) {
                    appendLog("Auto-start enabled: starting full stack...")
                    if (healthIndicatesBackendUsable(healthStatus)) {
                        backendExpectedRunning.set(true)
                        if (!JarvisPaths.isLocalRuntime()) {
                            appendLog("Backend core services already UP, revalidating Kubernetes runtime + observability...")
                            startAll()
                        } else {
                            if (!isDesktopRunning()) {
                                appendLog("Backend core services already UP, starting desktop...")
                                startDesktop()
                            } else {
                                appendLog("Desktop already running")
                            }
                        }
                    } else {
                        startAll()
                    }
                }
            }
        }
    }

    private fun confirmResetDialog(): Boolean? {
        val future = java.util.concurrent.CompletableFuture<Boolean?>()
        Platform.runLater {
            val alert = Alert(Alert.AlertType.CONFIRMATION)
            alert.title = "Reset Jarvis"
            alert.headerText = "Reset jarvis namespace"
            alert.contentText = "Choose reset mode:\n\n" +
                "- Preserve data: keeps existing PVCs\n" +
                "- Wipe data: deletes namespace (data loss)"
            val preserve = ButtonType("Preserve data", ButtonBar.ButtonData.OK_DONE)
            val wipe = ButtonType("Wipe data", ButtonBar.ButtonData.OTHER)
            alert.buttonTypes.setAll(preserve, wipe, ButtonType.CANCEL)
            val result = alert.showAndWait()
            val choice = result.orElse(ButtonType.CANCEL)
            future.complete(
                when (choice) {
                    preserve -> false
                    wipe -> true
                    else -> null
                }
            )
        }
        return future.get(2, TimeUnit.MINUTES)
    }

    private fun findLatestAcceptanceReport(): Path? {
        return try {
            val dir = JarvisPaths.logs
            if (!Files.exists(dir)) {
                return null
            }
            Files.newDirectoryStream(dir, "acceptance-*.txt").use { stream ->
                stream.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun runCommand(
        command: List<String>,
        workingDir: java.nio.file.Path,
        silent: Boolean = false,
        env: Map<String, String> = emptyMap()
    ): Int {
        return try {
            val builder = ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
            if (env.isNotEmpty()) {
                builder.environment().putAll(env)
            }
            val process = builder.start()
            process.inputStream.bufferedReader().forEachLine { line ->
                if (!silent) {
                    Platform.runLater { appendLog(line) }
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            logger.error("Command failed: $command", e)
            if (!silent) {
                Platform.runLater { appendLog("ERROR: ${e.message}") }
            }
            -1
        }
    }

    private fun buildPrivilegedCommand(script: String, env: Map<String, String>, args: List<String> = emptyList()): List<String> {
        val cmd = mutableListOf("pkexec", "/usr/bin/env")
        env.forEach { (k, v) -> cmd.add("$k=$v") }
        cmd.add(script)
        cmd.addAll(args)
        return cmd
    }

    private fun commandExists(cmd: String): Boolean {
        return try {
            val process = ProcessBuilder("bash", "-lc", "command -v $cmd")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun detectGpu(): Boolean {
        return commandExists("nvidia-smi") || Files.exists(Paths.get("/proc/driver/nvidia/version"))
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
                diagnostics.appendLine("Runtime target: ${JarvisPaths.describeRuntimeTarget()}")
                diagnostics.appendLine("")
                diagnostics.appendLine("--- System ---")
                diagnostics.appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                diagnostics.appendLine("Java: ${System.getProperty("java.version")}")
                diagnostics.appendLine("")
                
                diagnostics.appendLine("--- Backend Bootstrap ---")
                if (backendPid != null) {
                    val isAlive = try {
                        java.lang.ProcessHandle.of(backendPid).map { it.isAlive }.orElse(false)
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
                startAllButton.isDisable = false
                stopAllButton.isDisable = true
                statusLabel.style = "-fx-text-fill: gray; -fx-font-weight: bold;"
            }
            LauncherStatus.STARTING -> {
                startButton.isDisable = true
                stopButton.isDisable = false
                startDesktopButton.isDisable = true
                startAllButton.isDisable = true
                stopAllButton.isDisable = false
                statusLabel.style = "-fx-text-fill: orange; -fx-font-weight: bold;"
            }
            LauncherStatus.READY -> {
                startButton.isDisable = true
                stopButton.isDisable = false
                startDesktopButton.isDisable = false
                startAllButton.isDisable = true
                stopAllButton.isDisable = false
                statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
            }
            LauncherStatus.DEGRADED -> {
                startButton.isDisable = true
                stopButton.isDisable = false
                startDesktopButton.isDisable = false  // Desktop available in DEGRADED (core works)
                startAllButton.isDisable = true
                stopAllButton.isDisable = false
                statusLabel.style = "-fx-text-fill: orange; -fx-font-weight: bold;"
            }
            LauncherStatus.ERROR -> {
                startButton.isDisable = false
                stopButton.isDisable = false
                startDesktopButton.isDisable = true
                startAllButton.isDisable = false
                stopAllButton.isDisable = false
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
        val show = {
            val alert = Alert(Alert.AlertType.ERROR)
            alert.title = "Error"
            alert.headerText = title
            alert.contentText = message
            alert.showAndWait()
        }
        if (Platform.isFxApplicationThread()) {
            show()
        } else {
            Platform.runLater { show() }
        }
    }

    private fun showInfo(title: String, message: String) {
        val show = {
            val alert = Alert(Alert.AlertType.INFORMATION)
            alert.title = title
            alert.headerText = title
            alert.contentText = message
            alert.showAndWait()
        }
        if (Platform.isFxApplicationThread()) {
            show()
        } else {
            Platform.runLater { show() }
        }
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
