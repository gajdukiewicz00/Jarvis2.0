package org.jarvis.desktop.features.settings

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.i18n.I18n
import org.jarvis.desktop.service.DesktopServiceHealthChecker
import org.jarvis.desktop.shell.ShellRouteContent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SettingsView(
    apiClient: ApiClient,
    private val onLogout: () -> Unit
) : ScrollPane(), ShellRouteContent {
    private val serviceChecker = DesktopServiceHealthChecker(apiClient = apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-settings").apply { isDaemon = true }
    }
    private val checkInFlight = AtomicBoolean(false)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private val languageOptions = I18n.supportedLocales.map(::LocaleOption)

    private val subtitleLabel = Label()
    private val feedbackPill = statusPill("Settings")
    private val feedbackLabel = Label(
        "Desktop settings follow active runtime endpoints unless a manual override is pinned."
    ).apply {
        styleClass += "settings-feedback-text"
        isWrapText = true
    }

    private val saveButton = Button("Save changes")
    private val recheckButton = Button("Re-check services")
    private val logoutButton = Button("Logout")

    private val manualOverrideCheckBox = CheckBox("Pin API Gateway URL manually")
    private val gatewayUrlField = TextField()
    private val languageCombo = ComboBox<LocaleOption>()

    private val selectionModeLabel = valueLabel()
    private val sourceLabel = codeValueLabel()
    private val reasonLabel = valueLabel("settings-decision-value")
    private val gatewayBaseLabel = codeValueLabel()
    private val apiBaseLabel = codeValueLabel()
    private val voiceWsLabel = codeValueLabel()
    private val pcControlWsLabel = codeValueLabel()
    private val effectiveLocaleLabel = valueLabel()
    private val voiceLanguageLabel = codeValueLabel()
    private val themeLabel = valueLabel()

    private val serviceSummaryPill = statusPill("Checks idle")
    private val serviceMetaLabel = Label("Service checks have not run in this shell session yet.").apply {
        styleClass += "settings-meta-label"
        isWrapText = true
    }
    private val serviceChecksContainer = VBox(10.0).apply {
        styleClass += "settings-check-list"
    }

    private val authStatusLabel = valueLabel()
    private val usernameLabel = valueLabel()
    private val roleLabel = valueLabel()
    private val userIdLabel = codeValueLabel()
    private val tokenStateLabel = valueLabel()

    private val buildVersionLabel = valueLabel()
    private val buildStackLabel = valueLabel()
    private val buildThemeLabel = valueLabel()
    private val buildRuntimeLabel = valueLabel()

    private val configListener: (ResolvedDesktopConfig) -> Unit = { config ->
        Platform.runLater { renderConfig(config) }
    }

    init {
        styleClass += "shell-route-scroll"
        isFitToWidth = true
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        content = buildContent()

        languageCombo.items.addAll(languageOptions)
        manualOverrideCheckBox.setOnAction {
            gatewayUrlField.isDisable = !manualOverrideCheckBox.isSelected
        }
        saveButton.setOnAction { saveSettings() }
        recheckButton.setOnAction { checkServices() }
        logoutButton.setOnAction { logout() }

        AppConfig.addListener(configListener)
    }

    override fun onRouteActivated() {
        syncCurrentConfig()
        renderSessionDetails()
        renderBuildInfo()
        if (serviceChecksContainer.children.isEmpty()) {
            checkServices()
        }
    }

    override fun onShellShutdown() {
        AppConfig.removeListener(configListener)
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(16.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(8.0).apply {
                children += Label("Settings").apply { styleClass += "shell-page-title" }
                children += subtitleLabel.apply {
                    styleClass += "shell-page-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += HBox(12.0).apply {
                styleClass += "settings-header-actions"
                children.addAll(
                    saveButton.apply { styleClass += "shell-action-button" },
                    recheckButton.apply { styleClass += "shell-action-button" }
                )
            }
        }

        val feedbackRow = HBox(12.0).apply {
            styleClass += "settings-feedback-row"
            alignment = Pos.CENTER_LEFT
            children += feedbackPill
            children += feedbackLabel
        }

        val topCards = FlowPane(16.0, 16.0).apply {
            styleClass += "settings-card-grid"
            children.addAll(
                environmentCard(),
                resolvedEndpointsCard(),
                generalSettingsCard()
            )
        }

        val bottomCards = FlowPane(16.0, 16.0).apply {
            styleClass += "settings-card-grid"
            children.addAll(
                accountCard(),
                aboutCard()
            )
        }

        return VBox(18.0).apply {
            styleClass += "shell-settings-view"
            padding = Insets(24.0)
            children.addAll(header, feedbackRow, topCards, servicesCard(), bottomCards)
        }
    }

    private fun environmentCard(): VBox {
        val grid = formGrid().apply {
            add(settingLabel("Endpoint mode"), 0, 0)
            add(selectionModeLabel, 1, 0)
            add(settingLabel("Manual override"), 0, 1)
            add(manualOverrideCheckBox, 1, 1)
            add(settingLabel("Pinned gateway URL"), 0, 2)
            add(gatewayUrlField.apply { promptText = "https://127.0.0.1:18080" }, 1, 2)
            add(settingLabel("Active source"), 0, 3)
            add(sourceLabel, 1, 3)
            add(settingLabel("Endpoint decision"), 0, 4)
            add(reasonLabel, 1, 4)
        }

        return sectionCard(
            title = "Environment and endpoint policy",
            subtitle = "Control whether the desktop follows active local runtime endpoints or pins a manual gateway URL.",
            body = grid
        )
    }

    private fun resolvedEndpointsCard(): VBox {
        val grid = formGrid().apply {
            add(settingLabel("Gateway base"), 0, 0)
            add(gatewayBaseLabel, 1, 0)
            add(settingLabel("API client"), 0, 1)
            add(apiBaseLabel, 1, 1)
            add(settingLabel("Voice WebSocket"), 0, 2)
            add(voiceWsLabel, 1, 2)
            add(settingLabel("PC Control WebSocket"), 0, 3)
            add(pcControlWsLabel, 1, 3)
            add(settingLabel("Voice language"), 0, 4)
            add(voiceLanguageLabel, 1, 4)
        }

        return sectionCard(
            title = "Resolved endpoints",
            subtitle = "Read-only summary of the exact URLs the unified shell will use right now.",
            body = grid
        )
    }

    private fun generalSettingsCard(): VBox {
        val grid = formGrid().apply {
            add(settingLabel("Locale"), 0, 0)
            add(languageCombo, 1, 0)
            add(settingLabel("Effective locale"), 0, 1)
            add(effectiveLocaleLabel, 1, 1)
            add(settingLabel("Theme"), 0, 2)
            add(themeLabel, 1, 2)
        }

        return sectionCard(
            title = "General settings",
            subtitle = "Locale is configurable. Theme is fixed to the unified shell dark direction in this product line.",
            body = grid
        )
    }

    private fun servicesCard(): VBox {
        val headerRow = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(4.0).apply {
                children += Label("Service checks").apply { styleClass += "shell-section-title" }
                children += Label(
                    "Observation-only checks for the currently resolved API and WebSocket endpoints."
                ).apply {
                    styleClass += "shell-section-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += serviceSummaryPill
        }

        return VBox(12.0).apply {
            styleClass += "shell-section-card"
            children.addAll(headerRow, serviceMetaLabel, serviceChecksContainer)
        }
    }

    private fun accountCard(): VBox {
        val grid = formGrid().apply {
            add(settingLabel("Session"), 0, 0)
            add(authStatusLabel, 1, 0)
            add(settingLabel("Username"), 0, 1)
            add(usernameLabel, 1, 1)
            add(settingLabel("Role"), 0, 2)
            add(roleLabel, 1, 2)
            add(settingLabel("User ID"), 0, 3)
            add(userIdLabel, 1, 3)
            add(settingLabel("Token state"), 0, 4)
            add(tokenStateLabel, 1, 4)
        }

        return sectionCard(
            title = "Account and session",
            subtitle = "Current identity resolved from the desktop token store.",
            body = VBox(16.0).apply {
                children += grid
                children += HBox(12.0).apply {
                    children += logoutButton.apply {
                        styleClass += "shell-action-button-danger"
                    }
                }
            }
        )
    }

    private fun aboutCard(): VBox {
        val grid = formGrid().apply {
            add(settingLabel("Product"), 0, 0)
            add(valueLabel("settings-plain-value").apply { text = "Jarvis Desktop Unified Shell" }, 1, 0)
            add(settingLabel("Version"), 0, 1)
            add(buildVersionLabel, 1, 1)
            add(settingLabel("Stack"), 0, 2)
            add(buildStackLabel, 1, 2)
            add(settingLabel("Theme"), 0, 3)
            add(buildThemeLabel, 1, 3)
            add(settingLabel("Runtime"), 0, 4)
            add(buildRuntimeLabel, 1, 4)
        }

        return sectionCard(
            title = "About and build info",
            subtitle = "Minimal build metadata for the new unified desktop application shell.",
            body = grid
        )
    }

    private fun sectionCard(title: String, subtitle: String, body: Node): VBox {
        return VBox(12.0).apply {
            styleClass += "shell-section-card"
            styleClass += "settings-section-card"
            prefWidth = 360.0
            minWidth = 320.0
            maxWidth = Double.MAX_VALUE
            children += Label(title).apply { styleClass += "shell-section-title" }
            children += Label(subtitle).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += body
        }
    }

    private fun formGrid(): GridPane {
        return GridPane().apply {
            styleClass += "settings-form-grid"
            hgap = 16.0
            vgap = 12.0
        }
    }

    private fun settingLabel(text: String): Label {
        return Label(text).apply { styleClass += "settings-field-label" }
    }

    private fun valueLabel(extraStyle: String? = null): Label {
        return Label().apply {
            styleClass += "settings-plain-value"
            extraStyle?.let(styleClass::add)
            isWrapText = true
        }
    }

    private fun codeValueLabel(): Label {
        return Label().apply {
            styleClass.addAll("settings-plain-value", "settings-code-value")
            isWrapText = true
        }
    }

    private fun saveSettings() {
        val locale = languageCombo.value?.locale ?: AppConfig.locale
        val manualOverride = manualOverrideCheckBox.isSelected
        val gatewayUrl = gatewayUrlField.text

        try {
            val resolved = AppConfig.saveSettings(
                apiGatewayBaseUrl = gatewayUrl,
                locale = locale,
                manualEndpointOverride = manualOverride
            )
            I18n.setLocale(locale)
            renderConfig(resolved)
            showFeedback(
                headline = if (resolved.usesManualEndpointOverride) "Manual override saved" else "Settings saved",
                message = if (resolved.usesManualEndpointOverride) {
                    "Unified shell will keep using the pinned gateway URL until manual override is disabled."
                } else {
                    "Unified shell will keep following active runtime endpoints automatically."
                },
                toneClass = "shell-status-tone-success"
            )
        } catch (e: IllegalStateException) {
            showFeedback(
                headline = "Settings rejected",
                message = e.message ?: "Configuration could not be applied.",
                toneClass = "shell-status-tone-error"
            )
        } catch (e: Exception) {
            showFeedback(
                headline = "Save failed",
                message = e.message ?: "Unexpected configuration error.",
                toneClass = "shell-status-tone-error"
            )
        }
    }

    private fun checkServices() {
        if (!checkInFlight.compareAndSet(false, true)) {
            return
        }

        recheckButton.isDisable = true
        serviceMetaLabel.text = "Checking current API and WebSocket targets..."
        serviceSummaryPill.text = "Checking"
        applyTone(serviceSummaryPill, "shell-status-tone-info")

        worker.execute {
            try {
                val resolved = serviceChecker.resolvedConfig()
                val checks = serviceChecker.checkAll()
                Platform.runLater {
                    renderServiceChecks(resolved, checks, Instant.now())
                    showFeedback(
                        headline = "Service check complete",
                        message = "Desktop endpoint probes refreshed for ${resolved.apiGatewayBaseUrl}.",
                        toneClass = "shell-status-tone-success"
                    )
                }
            } catch (e: Exception) {
                Platform.runLater {
                    serviceMetaLabel.text = e.message ?: "Service checks failed."
                    serviceSummaryPill.text = "Checks failed"
                    applyTone(serviceSummaryPill, "shell-status-tone-error")
                    showFeedback(
                        headline = "Service check failed",
                        message = e.message ?: "Desktop endpoint probes could not be completed.",
                        toneClass = "shell-status-tone-error"
                    )
                }
            } finally {
                checkInFlight.set(false)
                Platform.runLater {
                    recheckButton.isDisable = false
                }
            }
        }
    }

    private fun logout() {
        onLogout()
        renderSessionDetails()
        showFeedback(
            headline = "Session cleared",
            message = "Desktop tokens were removed from the unified shell session.",
            toneClass = "shell-status-tone-warning"
        )
    }

    private fun syncCurrentConfig() {
        try {
            renderConfig(AppConfig.reload())
        } catch (e: Exception) {
            renderConfig(AppConfig.current())
            showFeedback(
                headline = "Using last known config",
                message = e.message ?: "Could not reload current endpoint resolution.",
                toneClass = "shell-status-tone-warning"
            )
        }
    }

    private fun renderConfig(config: ResolvedDesktopConfig) {
        subtitleLabel.text = buildString {
            append("Manage desktop endpoints, locale, service probes, and session details for the unified shell.")
            append(" Current API gateway: ")
            append(config.apiGatewayBaseUrl)
        }

        manualOverrideCheckBox.isSelected = config.usesManualEndpointOverride
        gatewayUrlField.isDisable = !config.usesManualEndpointOverride
        gatewayUrlField.text = config.apiGatewayBaseUrl
        languageCombo.selectionModel.select(languageOptions.firstOrNull { it.matches(config.locale) })

        selectionModeLabel.text = if (config.usesManualEndpointOverride) {
            "Manual override pinned"
        } else {
            "Automatic runtime detection"
        }
        sourceLabel.text = config.apiGatewaySource.description
        reasonLabel.text = config.apiGatewayReason

        gatewayBaseLabel.text = config.apiGatewayBaseUrl
        apiBaseLabel.text = config.apiBaseUrl
        voiceWsLabel.text = config.voiceWebSocketUrl
        pcControlWsLabel.text = config.pcControlWebSocketUrl
        effectiveLocaleLabel.text = "${config.locale.toLanguageTag()}  |  ${config.locale.getDisplayName(config.locale)}"
        voiceLanguageLabel.text = config.voiceLanguage
        themeLabel.text = "Dark-only unified shell"
    }

    private fun renderServiceChecks(
        resolved: ResolvedDesktopConfig,
        checks: List<DesktopServiceHealthChecker.ServiceCheck>,
        checkedAt: Instant
    ) {
        val online = checks.count { it.status == DesktopServiceHealthChecker.Status.ONLINE }
        val unauthorized = checks.count { it.status == DesktopServiceHealthChecker.Status.UNAUTHORIZED }
        val offline = checks.count { it.status == DesktopServiceHealthChecker.Status.OFFLINE }

        serviceSummaryPill.text = when {
            offline > 0 -> "Endpoints degraded"
            unauthorized > 0 -> "Auth required"
            checks.isNotEmpty() && online == checks.size -> "All reachable"
            else -> "Checks updated"
        }
        applyTone(serviceSummaryPill, toneForChecks(checks))
        serviceMetaLabel.text = listOf(
            "Updated ${timeFormatter.format(checkedAt)}",
            "Source ${resolved.apiGatewaySource.description}",
            "Online $online",
            "Unauthorized $unauthorized",
            "Offline $offline"
        ).joinToString("  |  ")

        serviceChecksContainer.children.clear()
        checks.forEach { check ->
            serviceChecksContainer.children += VBox(8.0).apply {
                styleClass += "settings-check-row"
                children += HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children += Label(check.name).apply { styleClass += "settings-check-title" }
                    val spacer = Region()
                    HBox.setHgrow(spacer, Priority.ALWAYS)
                    children += spacer
                    children += statusPill(check.status.name).apply {
                        applyTone(this, toneFor(check.status))
                    }
                }
                children += Label(check.target).apply {
                    styleClass += "settings-check-target"
                    isWrapText = true
                }
                children += Label(check.detail.ifBlank { "No extra detail." }).apply {
                    styleClass += "settings-check-detail"
                    isWrapText = true
                }
            }
        }
    }

    private fun renderSessionDetails() {
        val authenticated = TokenManager.isAuthenticated()
        authStatusLabel.text = if (authenticated) "Authenticated" else "Not authenticated"
        usernameLabel.text = TokenManager.getUsername() ?: "Offline user"
        roleLabel.text = TokenManager.getUserRole() ?: "Unknown"
        userIdLabel.text = TokenManager.getUserId() ?: "Unavailable"
        tokenStateLabel.text = if (TokenManager.getAccessToken().isNullOrBlank()) {
            "No access token stored"
        } else {
            "Access token available"
        }
    }

    private fun renderBuildInfo() {
        buildVersionLabel.text = SettingsView::class.java.`package`?.implementationVersion ?: "0.1.0-SNAPSHOT"
        buildStackLabel.text = "Kotlin 1.9.20  |  JavaFX 21"
        buildThemeLabel.text = "Dark-only unified shell"
        buildRuntimeLabel.text = "${System.getProperty("java.version")}  |  ${System.getProperty("os.name")}"
    }

    private fun showFeedback(headline: String, message: String, toneClass: String) {
        feedbackPill.text = headline
        applyTone(feedbackPill, toneClass)
        feedbackLabel.text = message
    }

    private fun statusPill(text: String): Label {
        return Label(text).apply {
            styleClass.addAll("shell-status-pill", "shell-status-tone-muted")
        }
    }

    private fun applyTone(label: Label, toneClass: String) {
        label.styleClass.removeIf {
            it == "shell-status-pill" || it.startsWith("shell-status-tone-")
        }
        label.styleClass.addAll("shell-status-pill", toneClass)
    }

    private fun toneFor(status: DesktopServiceHealthChecker.Status): String {
        return when (status) {
            DesktopServiceHealthChecker.Status.ONLINE -> "shell-status-tone-success"
            DesktopServiceHealthChecker.Status.UNAUTHORIZED -> "shell-status-tone-warning"
            DesktopServiceHealthChecker.Status.OFFLINE -> "shell-status-tone-error"
        }
    }

    private fun toneForChecks(checks: List<DesktopServiceHealthChecker.ServiceCheck>): String {
        return when {
            checks.any { it.status == DesktopServiceHealthChecker.Status.OFFLINE } -> "shell-status-tone-error"
            checks.any { it.status == DesktopServiceHealthChecker.Status.UNAUTHORIZED } -> "shell-status-tone-warning"
            checks.isNotEmpty() && checks.all { it.status == DesktopServiceHealthChecker.Status.ONLINE } ->
                "shell-status-tone-success"
            else -> "shell-status-tone-muted"
        }
    }

    private data class LocaleOption(val locale: Locale) {
        fun matches(other: Locale): Boolean = locale.toLanguageTag() == other.toLanguageTag()

        override fun toString(): String {
            return locale.getDisplayName(locale).replaceFirstChar(Char::titlecase)
        }
    }
}
