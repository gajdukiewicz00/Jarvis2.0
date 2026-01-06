package org.jarvis.desktop.ui.tabs

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox

class SettingsTab(
    private val onLogout: () -> Unit
) {
    val tab = Tab("Settings")
    private val statusLabel = Label("")
    private val gatewayUrlField = TextField("http://localhost:8080/api/v1")
    private val serviceStatusArea = TextArea()

    init {
        val content = VBox(10.0)
        content.padding = Insets(10.0)

        // Title
        val title = Label("Settings")
        title.style = "-fx-font-size: 18px; -fx-font-weight: bold;"
        content.children.add(title)

        // Status label
        statusLabel.style = "-fx-font-weight: bold;"
        content.children.add(statusLabel)

        // Configuration Section
        val configSection = TitledPane()
        configSection.text = "Configuration"
        configSection.isCollapsible = false
        
        val configGrid = GridPane()
        configGrid.hgap = 10.0
        configGrid.vgap = 10.0
        configGrid.padding = Insets(10.0)

        // API Gateway URL
        configGrid.add(Label("API Gateway URL:"), 0, 0)
        gatewayUrlField.prefWidth = 400.0
        configGrid.add(gatewayUrlField, 1, 0)

        val saveBtn = Button("💾 Save")
        saveBtn.setOnAction {
            statusLabel.text = "✓ Configuration saved"
            statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
        }
        configGrid.add(saveBtn, 2, 0)

        configSection.content = configGrid
        content.children.add(configSection)

        // Service Status Section
        val statusSection = TitledPane()
        statusSection.text = "Service Status"
        statusSection.isCollapsible = false

        val statusBox = VBox(10.0)
        statusBox.padding = Insets(10.0)

        val checkBtn = Button("🔍 Check All Services")
        checkBtn.setOnAction { checkServiceStatus() }
        statusBox.children.add(checkBtn)

        serviceStatusArea.isEditable = false
        serviceStatusArea.prefHeight = 200.0
        serviceStatusArea.style = "-fx-font-family: monospace;"
        serviceStatusArea.text = "Click 'Check All Services' to test connectivity..."
        statusBox.children.add(serviceStatusArea)

        statusSection.content = statusBox
        content.children.add(statusSection)

        // Preferences Section
        val prefSection = TitledPane()
        prefSection.text = "Preferences"
        prefSection.isCollapsible = false

        val prefGrid = GridPane()
        prefGrid.hgap = 10.0
        prefGrid.vgap = 10.0
        prefGrid.padding = Insets(10.0)

        // Theme preference
        prefGrid.add(Label("Theme:"), 0, 0)
        val themeCombo = ComboBox<String>()
        themeCombo.items.addAll("Light", "Dark")
        themeCombo.value = "Light"
        prefGrid.add(themeCombo, 1, 0)

        // Language preference
        prefGrid.add(Label("Language:"), 0, 1)
        val langCombo = ComboBox<String>()
        langCombo.items.addAll("English", "Russian")
        langCombo.value = "Russian"
        prefGrid.add(langCombo, 1, 1)

        prefSection.content = prefGrid
        content.children.add(prefSection)

        // About Section
        val aboutSection = TitledPane()
        aboutSection.text = "About"
        aboutSection.isCollapsible = false

        val aboutBox = VBox(5.0)
        aboutBox.padding = Insets(10.0)
        aboutBox.children.addAll(
            Label("Jarvis 2.0 Desktop Client"),
            Label("Version: 0.1.0-SNAPSHOT"),
            Label("Microservices Architecture"),
            Label(""),
            Label("Services:"),
            Label("  • Voice Gateway (STT)"),
            Label("  • NLP Service"),
            Label("  • Orchestrator"),
            Label("  • PC Control"),
            Label("  • Smart Home"),
            Label("  • Life Tracker"),
            Label("  • Analytics")
        )
        aboutSection.content = aboutBox
        content.children.add(aboutSection)

        // Account Section
        val accountSection = TitledPane()
        accountSection.text = "Account"
        accountSection.isCollapsible = false

        val accountBox = VBox(10.0)
        accountBox.padding = Insets(10.0)

        val logoutDescription = Label("Logout to switch accounts or exit securely.")
        val logoutBtn = Button("🚪 Logout")
        logoutBtn.style = "-fx-background-color: #d9534f; -fx-text-fill: white;"
        logoutBtn.setOnAction {
            statusLabel.text = "Logging out..."
            statusLabel.style = "-fx-text-fill: orange; -fx-font-weight: bold;"
            onLogout()
        }

        accountBox.children.addAll(logoutDescription, logoutBtn)
        accountSection.content = accountBox
        content.children.add(accountSection)

        tab.content = content
        tab.isClosable = false
    }

    private fun checkServiceStatus() {
        statusLabel.text = "Checking services..."
        statusLabel.style = "-fx-text-fill: blue; -fx-font-weight: bold;"

        Thread {
            val baseUrl = gatewayUrlField.text.removeSuffix("/api/v1")
            val results = StringBuilder()
            results.append(String.format("%-25s %10s\n", "Service", "Status"))
            results.append("-".repeat(37) + "\n")

            val services = listOf(
                "API Gateway" to "$baseUrl/actuator/health",
                "Voice Gateway" to "$baseUrl/api/v1/voice/command",
                "Orchestrator" to "$baseUrl/api/v1/orchestrator/execute",
                "PC Control" to "$baseUrl/api/v1/pc/action",
                "Life Tracker" to "$baseUrl/api/v1/life/finance/expenses",
                "Smart Home" to "$baseUrl/api/v1/smarthome/devices/test/action",
                "Analytics" to "$baseUrl/api/v1/analytics/overview"
            )

            for ((name, url) in services) {
                val status = try {
                    java.net.http.HttpClient.newHttpClient()
                        .send(
                            java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(url))
                                .timeout(java.time.Duration.ofSeconds(2))
                                .GET()
                                .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString()
                        )
                    "✓ Online"
                } catch (e: Exception) {
                    "✗ Offline"
                }
                results.append(String.format("%-25s %10s\n", name, status))
            }

            Platform.runLater {
                serviceStatusArea.text = results.toString()
                statusLabel.text = "✓ Service check complete"
                statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
            }
        }.start()
    }
}
