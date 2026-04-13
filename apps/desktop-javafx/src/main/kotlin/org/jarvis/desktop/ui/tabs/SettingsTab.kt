package org.jarvis.desktop.ui.tabs

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.TitledPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.i18n.I18n
import org.jarvis.desktop.service.DesktopServiceHealthChecker
import java.util.Locale

class SettingsTab(
    private val apiClient: ApiClient,
    private val onLogout: () -> Unit
) {
    val tab = Tab("Settings")
    private val statusLabel = Label("")
    private val gatewayUrlField = TextField(AppConfig.apiGatewayBaseUrl)
    private val serviceStatusArea = TextArea()
    private val languageCombo = ComboBox<LanguageOption>()
    private val effectiveApiLabel = Label()
    private val effectiveVoiceLabel = Label()
    private val effectivePcLabel = Label()
    private val sourceLabel = Label()
    private val reasonLabel = Label()
    private val manualOverrideCheckBox = CheckBox("Pin API Gateway URL manually")
    private val serviceChecker = DesktopServiceHealthChecker(apiClient = apiClient)

    init {
        val content = VBox(10.0)
        content.padding = Insets(10.0)

        val title = Label("Settings")
        title.style = "-fx-font-size: 18px; -fx-font-weight: bold;"
        content.children.add(title)

        statusLabel.style = "-fx-font-weight: bold;"
        content.children.add(statusLabel)

        val configSection = TitledPane()
        configSection.text = "Configuration"
        configSection.isCollapsible = false

        val configGrid = GridPane()
        configGrid.hgap = 10.0
        configGrid.vgap = 10.0
        configGrid.padding = Insets(10.0)

        configGrid.add(Label("API Gateway URL:"), 0, 0)
        gatewayUrlField.prefWidth = 400.0
        configGrid.add(gatewayUrlField, 1, 0)
        manualOverrideCheckBox.setOnAction {
            gatewayUrlField.isDisable = !manualOverrideCheckBox.isSelected
        }
        configGrid.add(manualOverrideCheckBox, 1, 1, 2, 1)

        configGrid.add(Label("Language:"), 0, 2)
        languageCombo.items.addAll(
            LanguageOption("English", Locale.ENGLISH),
            LanguageOption("Polski", Locale("pl", "PL")),
            LanguageOption("Русский", Locale("ru", "RU"))
        )
        languageCombo.selectionModel.select(
            languageCombo.items.firstOrNull { it.locale.language == AppConfig.locale.language }
        )
        configGrid.add(languageCombo, 1, 2)

        val saveBtn = Button("💾 Save")
        saveBtn.setOnAction { saveSettings() }
        configGrid.add(saveBtn, 2, 0)

        configGrid.add(Label("Effective API client:"), 0, 3)
        effectiveApiLabel.style = "-fx-font-family: monospace;"
        configGrid.add(effectiveApiLabel, 1, 3, 2, 1)

        configGrid.add(Label("Effective Voice WS:"), 0, 4)
        effectiveVoiceLabel.style = "-fx-font-family: monospace;"
        configGrid.add(effectiveVoiceLabel, 1, 4, 2, 1)

        configGrid.add(Label("Effective PC Control WS:"), 0, 5)
        effectivePcLabel.style = "-fx-font-family: monospace;"
        configGrid.add(effectivePcLabel, 1, 5, 2, 1)

        configGrid.add(Label("Endpoint source:"), 0, 6)
        configGrid.add(sourceLabel, 1, 6, 2, 1)

        configGrid.add(Label("Endpoint decision:"), 0, 7)
        reasonLabel.isWrapText = true
        reasonLabel.maxWidth = 500.0
        configGrid.add(reasonLabel, 1, 7, 2, 1)

        configSection.content = configGrid
        content.children.add(configSection)

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
        serviceStatusArea.text = "Click 'Check All Services' to test the active runtime endpoints..."
        statusBox.children.add(serviceStatusArea)

        statusSection.content = statusBox
        content.children.add(statusSection)

        val prefSection = TitledPane()
        prefSection.text = "Preferences"
        prefSection.isCollapsible = false

        val prefGrid = GridPane()
        prefGrid.hgap = 10.0
        prefGrid.vgap = 10.0
        prefGrid.padding = Insets(10.0)

        prefGrid.add(Label("Theme:"), 0, 0)
        val themeCombo = ComboBox<String>()
        themeCombo.items.addAll("Light", "Dark")
        themeCombo.value = "Light"
        prefGrid.add(themeCombo, 1, 0)

        prefGrid.add(Label("Language:"), 0, 1)
        prefGrid.add(Label(languageCombo.value?.displayName ?: AppConfig.locale.displayLanguage), 1, 1)

        prefSection.content = prefGrid
        content.children.add(prefSection)

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
        refreshResolvedConfig(AppConfig.current())
    }

    private fun saveSettings() {
        val selectedLocale = languageCombo.value?.locale ?: AppConfig.locale

        try {
            val resolved = AppConfig.saveSettings(
                apiGatewayBaseUrl = gatewayUrlField.text,
                locale = selectedLocale,
                manualEndpointOverride = manualOverrideCheckBox.isSelected
            )
            I18n.setLocale(selectedLocale)
            refreshResolvedConfig(resolved)
            statusLabel.text = if (resolved.usesManualEndpointOverride) {
                "✓ Configuration saved. Manual endpoint override is active."
            } else {
                "✓ Configuration saved. Desktop will follow active runtime endpoints automatically."
            }
            statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
        } catch (e: IllegalStateException) {
            statusLabel.text = "✗ ${e.message}"
            statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"
        }
    }

    private fun checkServiceStatus() {
        statusLabel.text = "Checking services..."
        statusLabel.style = "-fx-text-fill: blue; -fx-font-weight: bold;"

        Thread {
            val results = StringBuilder()
            val resolved = serviceChecker.resolvedConfig()
            val checks = serviceChecker.checkAll()

            results.append("Resolved runtime endpoints\n")
            results.append("API client     ${resolved.apiBaseUrl}\n")
            results.append("Voice WS       ${resolved.voiceWebSocketUrl}\n")
            results.append("PC Control WS  ${resolved.pcControlWebSocketUrl}\n")
            results.append("Source         ${resolved.apiGatewaySource.description}\n")
            results.append("Decision       ${resolved.apiGatewayReason}\n\n")
            results.append(String.format("%-18s %-13s %s\n", "Service", "Status", "Target"))
            results.append("-".repeat(96) + "\n")

            for (check in checks) {
                val status = when (check.status) {
                    DesktopServiceHealthChecker.Status.ONLINE -> "ONLINE"
                    DesktopServiceHealthChecker.Status.UNAUTHORIZED -> "UNAUTHORIZED"
                    DesktopServiceHealthChecker.Status.OFFLINE -> "OFFLINE"
                }
                results.append(String.format("%-18s %-13s %s\n", check.name, status, check.target))
                if (check.detail.isNotBlank()) {
                    results.append("  ${check.detail}\n")
                }
            }

            Platform.runLater {
                serviceStatusArea.text = results.toString()
                statusLabel.text = "✓ Service check complete"
                statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
            }
        }.apply {
            isDaemon = true
            name = "jarvis-desktop-settings-check"
            start()
        }
    }

    private fun refreshResolvedConfig(config: ResolvedDesktopConfig) {
        manualOverrideCheckBox.isSelected = config.usesManualEndpointOverride
        gatewayUrlField.isDisable = !config.usesManualEndpointOverride
        gatewayUrlField.text = config.apiGatewayBaseUrl
        effectiveApiLabel.text = config.apiBaseUrl
        effectiveVoiceLabel.text = config.voiceWebSocketUrl
        effectivePcLabel.text = config.pcControlWebSocketUrl
        sourceLabel.text = config.apiGatewaySource.description
        reasonLabel.text = config.apiGatewayReason
    }

    data class LanguageOption(val displayName: String, val locale: Locale) {
        override fun toString(): String = displayName
    }
}
