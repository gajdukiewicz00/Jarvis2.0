package org.jarvis.desktop.ui.tabs

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.service.SystemControlService
import org.slf4j.LoggerFactory

class PcControlTab(private val apiClient: ApiClient) {
    val tab = Tab("PC Control")
    private val statusLabel = Label("")
    private val dependencySummaryLabel = Label("").apply {
        isWrapText = true
        style = "-fx-text-fill: #455a64;"
    }
    private val systemControl = SystemControlService()
    private val logger = LoggerFactory.getLogger(PcControlTab::class.java)
    private var missingDeps: Set<String> = emptySet()

    init {
        val content = VBox(15.0).apply {
            padding = Insets(20.0)
        }
        
        // Title
        content.children.add(Label("🖥️ PC Control").apply {
            style = "-fx-font-size: 20px; -fx-font-weight: bold;"
        })
        
        // Status label
        statusLabel.style = "-fx-font-weight: bold;"
        content.children.add(statusLabel)
        content.children.add(dependencySummaryLabel)
        
        // Check dependencies on init
        checkDependencies()
        content.children.add(Button("Refresh capabilities").apply {
            setOnAction { refresh() }
        })
        
        // === Volume Control ===
        val volumeSection = createSection("🔊 Volume", createVolumeControls())
        disableSectionIf(volumeSection, "pactl")
        content.children.add(volumeSection)
        
        // === Media Control ===
        val mediaSection = createSection("🎵 Media", createMediaControls())
        disableSectionIf(mediaSection, "playerctl")
        content.children.add(mediaSection)
        
        // === Applications ===
        content.children.add(createSection("📱 Apps", createAppControls()))
        
        // === Hotkeys ===
        val hotkeySection = createSection("⌨️ Hotkeys", createHotkeyControls())
        disableSectionIf(hotkeySection, "xdotool")
        content.children.add(hotkeySection)
        
        // === Windows ===
        val windowSection = createSection("🪟 Windows", createWindowControls())
        disableSectionIf(windowSection, "xdotool")
        content.children.add(windowSection)
        
        // === Scenarios ===
        content.children.add(createSection("🎯 Scenarios", createScenarioControls()))
        
        // === Text Command (for orchestrator integration) ===
        content.children.add(createSection("💬 Text Command", createTextCommandBox()))

        tab.content = ScrollPane(content).apply {
            isFitToWidth = true
        }
        tab.isClosable = false
    }

    fun refresh() {
        checkDependencies()
    }
    
    private fun createSection(title: String, controls: FlowPane): VBox {
        return VBox(8.0).apply {
            children.add(Label(title).apply {
                style = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #555;"
            })
            children.add(controls)
            children.add(Separator())
        }
    }
    
    private fun createVolumeControls(): FlowPane {
        return FlowPane(10.0, 10.0).apply {
            children.addAll(
                createButton("🔊 Vol +10") { 
                    executeAction("Volume +10%") { systemControl.changeVolume(10, "+") }
                },
                createButton("🔉 Vol -10") { 
                    executeAction("Volume -10%") { systemControl.changeVolume(10, "-") }
                },
                createButton("🔇 Mute") { 
                    executeAction("Mute toggle") { systemControl.toggleMute() }
                },
                createButton("🔈 Vol 30%") {
                    executeAction("Volume 30%") { systemControl.setVolume(30) }
                },
                createButton("🔊 Vol 70%") {
                    executeAction("Volume 70%") { systemControl.setVolume(70) }
                }
            )
        }
    }
    
    private fun createMediaControls(): FlowPane {
        return FlowPane(10.0, 10.0).apply {
            children.addAll(
                createButton("⏮️ Prev") { 
                    executeAction("Previous track") { systemControl.mediaControl("PREV") }
                },
                createButton("⏯️ Play/Pause") { 
                    executeAction("Play/Pause") { systemControl.mediaControl("PLAY_PAUSE") }
                },
                createButton("⏭️ Next") { 
                    executeAction("Next track") { systemControl.mediaControl("NEXT") }
                },
                createButton("⏹️ Stop") { 
                    executeAction("Stop") { systemControl.mediaControl("STOP") }
                }
            )
        }
    }
    
    private fun createAppControls(): FlowPane {
        return FlowPane(10.0, 10.0).apply {
            children.addAll(
                createButton("🌐 Browser") { 
                    executeAction("Opening Browser") { systemControl.openApp("browser") }
                },
                createButton("▶️ YouTube") { 
                    executeAction("Opening YouTube") { systemControl.openApp("youtube") }
                },
                createButton("💻 VS Code") { 
                    executeAction("Opening VS Code") { systemControl.openApp("code") }
                },
                createButton("🧠 IntelliJ") { 
                    executeAction("Opening IntelliJ") { systemControl.openApp("idea") }
                },
                createButton("🎵 Spotify") { 
                    executeAction("Opening Spotify") { systemControl.openApp("spotify") }
                },
                createButton("✈️ Telegram") { 
                    executeAction("Opening Telegram") { systemControl.openApp("telegram") }
                },
                createButton("💬 Discord") { 
                    executeAction("Opening Discord") { systemControl.openApp("discord") }
                },
                createButton("📁 Files") { 
                    executeAction("Opening Files") { systemControl.openApp("nautilus") }
                },
                createButton("🖥️ Terminal") { 
                    executeAction("Opening Terminal") { systemControl.openApp("terminal") }
                }
            )
        }
    }
    
    private fun createHotkeyControls(): FlowPane {
        return FlowPane(10.0, 10.0).apply {
            children.addAll(
                createButton("Alt+Tab") { 
                    executeAction("Alt+Tab") { systemControl.executeHotkey("alt+Tab") }
                },
                createButton("Super (Menu)") { 
                    executeAction("Super key") { systemControl.executeHotkey("super") }
                },
                createButton("Super+D (Desktop)") { 
                    executeAction("Show Desktop") { systemControl.executeHotkey("super+d") }
                },
                createButton("Copy (Ctrl+C)") { 
                    executeAction("Copy") { systemControl.executeHotkey("ctrl+c") }
                },
                createButton("Paste (Ctrl+V)") { 
                    executeAction("Paste") { systemControl.executeHotkey("ctrl+v") }
                }
            )
        }
    }
    
    private fun createWindowControls(): FlowPane {
        return FlowPane(10.0, 10.0).apply {
            children.addAll(
                createButton("➖ Minimize") { 
                    executeAction("Minimize") { systemControl.windowAction("MINIMIZE") }
                },
                createButton("⬜ Maximize") { 
                    executeAction("Maximize") { systemControl.windowAction("MAXIMIZE") }
                },
                createButton("⛶ Fullscreen") { 
                    executeAction("Fullscreen") { systemControl.windowAction("FULLSCREEN") }
                },
                createButton("🔒 Lock Screen") { 
                    executeAction("Lock Screen") { systemControl.lockScreen() }
                }
            )
        }
    }
    
    private fun createScenarioControls(): FlowPane {
        return FlowPane(10.0, 10.0).apply {
            children.addAll(
                createButton("💼 Work Mode") { 
                    executeAction("Work Mode") { systemControl.executeScenario("work") }
                },
                createButton("🎬 Rest Mode") { 
                    executeAction("Rest Mode") { systemControl.executeScenario("rest") }
                },
                createButton("🧘 Focus Mode") { 
                    executeAction("Focus Mode") { systemControl.executeScenario("focus") }
                },
                createButton("🔔 Test Notification") {
                    executeAction("Notification") { 
                        systemControl.showNotification("Jarvis", "PC Control is working! ✓")
                    }
                }
            )
        }
    }
    
    private fun createTextCommandBox(): FlowPane {
        val cmdField = TextField().apply {
            promptText = "Enter command (e.g., 'прибавь громкость')"
            prefWidth = 350.0
        }
        
        val sendBtn = Button("Send to Orchestrator")
        sendBtn.setOnAction {
            val text = cmdField.text
            if (text.isNotBlank()) {
                try {
                    apiClient.post("/orchestrator/execute", """{"text": "$text"}""")
                    showStatus("✓ Command sent: $text", true)
                    cmdField.text = ""
                } catch (e: Exception) {
                    showStatus("✗ Failed: ${e.message}", false)
                }
            }
        }
        
        return FlowPane(10.0, 10.0).apply {
            children.addAll(cmdField, sendBtn)
        }
    }
    
    private fun createButton(label: String, action: () -> Unit): Button {
        return Button(label).apply {
            prefWidth = 130.0
            setOnAction { action() }
        }
    }
    
    private fun executeAction(description: String, action: () -> Result<Unit>) {
        logger.info("Executing: $description")
        val result = action()
        if (result.isSuccess) {
            showStatus("✓ $description", true)
        } else {
            showStatus("✗ $description: ${result.exceptionOrNull()?.message}", false)
        }
    }
    
    private fun showStatus(message: String, success: Boolean) {
        statusLabel.text = message
        statusLabel.style = if (success) {
            "-fx-text-fill: #2e7d32; -fx-font-weight: bold;"
        } else {
            "-fx-text-fill: #c62828; -fx-font-weight: bold;"
        }
    }
    
    private fun disableSectionIf(section: VBox, requiredUtility: String) {
        if (requiredUtility in missingDeps) {
            section.isDisable = true
            section.opacity = 0.5
        }
    }

    private fun checkDependencies() {
        statusLabel.text = "Checking desktop control capabilities..."
        statusLabel.style = "-fx-text-fill: #1565c0; -fx-font-weight: bold;"
        val deps = systemControl.checkDependencies()
        missingDeps = deps.filter { !it.value }.keys

        val availableDeps = deps.filterValues { it }.keys
        dependencySummaryLabel.text = when {
            deps.isEmpty() -> "No capability probes are registered for this desktop control helper."
            availableDeps.isEmpty() -> "No supported desktop automation utilities were detected on this machine."
            missingDeps.isEmpty() -> "Utilities ready: ${availableDeps.sorted().joinToString()}"
            else -> "Utilities ready: ${availableDeps.sorted().joinToString()} | Missing: ${missingDeps.sorted().joinToString()}"
        }

        if (availableDeps.isEmpty()) {
            showStatus("⚠️ No supported desktop automation utilities detected", false)
        } else if (missingDeps.isNotEmpty()) {
            showStatus("⚠️ Missing: ${missingDeps.joinToString()}. Install: sudo apt install ${missingDeps.joinToString(" ")}", false)
            logger.warn("Missing utilities: $missingDeps. Install with: sudo apt install ${missingDeps.joinToString(" ")}")
        } else {
            showStatus("✓ Desktop control helper is ready", true)
        }
    }
}
