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
    private val systemControl = SystemControlService()
    private val logger = LoggerFactory.getLogger(PcControlTab::class.java)

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
        
        // Check dependencies on init
        checkDependencies()
        
        // === Volume Control ===
        content.children.add(createSection("🔊 Volume", createVolumeControls()))
        
        // === Media Control ===
        content.children.add(createSection("🎵 Media", createMediaControls()))
        
        // === Applications ===
        content.children.add(createSection("📱 Apps", createAppControls()))
        
        // === Hotkeys ===
        content.children.add(createSection("⌨️ Hotkeys", createHotkeyControls()))
        
        // === Windows ===
        content.children.add(createSection("🪟 Windows", createWindowControls()))
        
        // === Scenarios ===
        content.children.add(createSection("🎯 Scenarios", createScenarioControls()))
        
        // === Text Command (for orchestrator integration) ===
        content.children.add(createSection("💬 Text Command", createTextCommandBox()))

        tab.content = ScrollPane(content).apply {
            isFitToWidth = true
        }
        tab.isClosable = false
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
    
    private fun checkDependencies() {
        val deps = systemControl.checkDependencies()
        val missing = deps.filter { !it.value }.keys
        if (missing.isNotEmpty()) {
            showStatus("⚠️ Missing utilities: ${missing.joinToString()}", false)
            logger.warn("Missing utilities: $missing. Install with: sudo apt install ${missing.joinToString(" ")}")
        }
    }
}
