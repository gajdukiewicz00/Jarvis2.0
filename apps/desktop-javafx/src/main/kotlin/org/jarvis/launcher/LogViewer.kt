package org.jarvis.launcher

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Log Viewer component for Launcher UI.
 * Displays log files with auto-scroll, highlighting, and file switching.
 */
class LogViewer {
    private val logger = LoggerFactory.getLogger(LogViewer::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val updateExecutor = Executors.newScheduledThreadPool(1)
    
    private lateinit var logTextArea: TextArea
    private lateinit var fileComboBox: ComboBox<String>
    private lateinit var autoScrollCheckBox: CheckBox
    private lateinit var statusLabel: Label
    
    private val autoScrollEnabled = AtomicBoolean(true)
    private val lastFileSize = AtomicLong(0)
    private var currentFile: Path? = null
    private var updateTask: java.util.concurrent.ScheduledFuture<*>? = null
    
    private val MAX_LINES = 1000  // Limit to last 1000 lines
    
    /**
     * Create the log viewer UI component.
     */
    fun createUI(): VBox {
        val root = VBox(10.0)
        root.padding = Insets(10.0)
        
        // Header with file selector and controls
        val headerBox = HBox(10.0)
        headerBox.alignment = javafx.geometry.Pos.CENTER_LEFT
        
        val fileLabel = Label("Log file:")
        fileComboBox = ComboBox()
        fileComboBox.items.addAll("launcher.log", "backend-launch.log")
        fileComboBox.value = "launcher.log"
        fileComboBox.setOnAction { switchLogFile() }
        
        autoScrollCheckBox = CheckBox("Auto-scroll")
        autoScrollCheckBox.isSelected = true
        autoScrollCheckBox.setOnAction {
            autoScrollEnabled.set(autoScrollCheckBox.isSelected)
        }
        
        val refreshButton = Button("Refresh")
        refreshButton.setOnAction { refreshLog() }
        
        statusLabel = Label("Ready")
        statusLabel.style = "-fx-font-size: 10px; -fx-text-fill: gray;"
        
        headerBox.children.addAll(fileLabel, fileComboBox, autoScrollCheckBox, refreshButton, statusLabel)
        
        // Log text area with highlighting
        logTextArea = TextArea()
        logTextArea.isEditable = false
        logTextArea.style = "-fx-font-family: 'Monaco', 'Courier New', monospace; -fx-font-size: 11px;"
        logTextArea.prefRowCount = 30
        
        // Wrap in scroll pane
        val scrollPane = ScrollPane(logTextArea)
        scrollPane.isFitToWidth = true
        scrollPane.isFitToHeight = true
        scrollPane.vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        scrollPane.hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        
        root.children.addAll(headerBox, scrollPane)
        VBox.setVgrow(scrollPane, Priority.ALWAYS)
        
        // Start auto-update
        startAutoUpdate()
        
        // Load initial file
        switchLogFile()
        
        return root
    }
    
    private fun switchLogFile() {
        val fileName = fileComboBox.value ?: "launcher.log"
        currentFile = when (fileName) {
            "launcher.log" -> JarvisPaths.launcherLog
            "backend-launch.log" -> JarvisPaths.backendLaunchLog
            else -> JarvisPaths.launcherLog
        }
        lastFileSize.set(0)
        refreshLog()
    }
    
    private fun refreshLog() {
        executor.execute {
            try {
                val file = currentFile ?: return@execute
                if (!Files.exists(file)) {
                    Platform.runLater {
                        logTextArea.text = "Log file not found: $file"
                        statusLabel.text = "File not found"
                    }
                    return@execute
                }
                
                // Read last MAX_LINES lines
                val lines = Files.readAllLines(file)
                val startIndex = maxOf(0, lines.size - MAX_LINES)
                val recentLines = lines.subList(startIndex, lines.size)
                
                Platform.runLater {
                    // Stage 6: Mask sensitive data in UI (file not changed, only display)
                    val secretKeys = SecurityUtils.getCommonSecretKeys()
                    val maskedLines = recentLines.map { line ->
                        SecurityUtils.maskSensitiveData(line, secretKeys)
                    }
                    val text = maskedLines.joinToString("\n")
                    logTextArea.text = text
                    
                    // Apply highlighting (simple text-based, since TextArea doesn't support rich text easily)
                    // For now, we'll use a simple approach: just show the text
                    // Full highlighting would require RichTextFX or custom TextFlow
                    
                    // Auto-scroll to bottom
                    if (autoScrollEnabled.get()) {
                        logTextArea.positionCaret(logTextArea.text.length)
                    }
                    
                    val fileName = file.fileName.toString()
                    val runLabel = if (fileName == "backend-launch.log") "current run" else "launcher"
                    statusLabel.text = "Viewing $fileName ($runLabel), loaded ${recentLines.size} lines (${lines.size} total, secrets masked)"
                }
                
                lastFileSize.set(Files.size(file))
            } catch (e: Exception) {
                logger.error("Failed to read log file", e)
                Platform.runLater {
                    statusLabel.text = "Error: ${e.message?.take(50)}"
                }
            }
        }
    }
    
    private fun startAutoUpdate() {
        updateTask = updateExecutor.scheduleAtFixedRate({
            try {
                val file = currentFile ?: return@scheduleAtFixedRate
                if (!Files.exists(file)) return@scheduleAtFixedRate
                
                val currentSize = Files.size(file)
                if (currentSize != lastFileSize.get()) {
                    // File changed, refresh
                    refreshLog()
                }
            } catch (e: Exception) {
                logger.debug("Auto-update check failed", e)
            }
        }, 2, 2, TimeUnit.SECONDS)  // Check every 2 seconds
    }
    
    fun stop() {
        updateTask?.cancel(false)
        updateExecutor.shutdown()
        executor.shutdown()
    }
}
