package org.jarvis.desktop.features.security

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.features.common.ShellPanelSupport
import org.jarvis.desktop.shell.ShellRouteContent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Security / Privacy panel — surface and toggle privacy mode through the
 * gateway's `/api/v1/security/auth/privacy` endpoints. When privacy is ON,
 * sensitive observation/logging is expected to be suppressed by the backend.
 */
class SecurityView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = SecurityReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-security").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Privacy")
    private val statusHeadline = Label("Checking privacy status…").apply {
        styleClass += "shell-section-title"
        isWrapText = true
    }
    private val statusDetail = ShellPanelSupport.sectionSubtitle(
        "Privacy mode pauses sensitive observation and proactive logging on the host until you turn it back off."
    )

    private val enableButton = Button("Enable privacy").apply {
        styleClass += "shell-action-button"
        setOnAction { runAction("Enabling privacy…") { readModel.enablePrivacy() } }
    }
    private val disableButton = Button("Disable privacy").apply {
        styleClass += "shell-action-button-danger"
        setOnAction { runAction("Disabling privacy…") { readModel.disablePrivacy() } }
    }
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { runAction("Refreshing privacy status…") { readModel.status() } }
    }

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-security-view"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
    }

    override fun onRouteActivated() {
        runAction("Loading privacy status…") { readModel.status() }
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(6.0).apply {
                children += Label("Security / Privacy").apply { styleClass += "shell-page-title" }
                children += Label("Control privacy mode and review its current state.").apply {
                    styleClass += "shell-page-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += statusPill
            children += refreshButton
        }

        val statusCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += statusHeadline
            children += statusDetail
            children += HBox(12.0, enableButton, disableButton).apply { alignment = Pos.CENTER_LEFT }
        }

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, statusCard)
        }
    }

    private fun runAction(progress: String, block: () -> SecurityReadModel.PrivacySnapshot) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        setBusy(true)
        statusPill.text = "Working"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusDetail.text = progress

        worker.execute {
            try {
                val snapshot = block()
                Platform.runLater { render(snapshot) }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusHeadline.text = "Privacy controls временно недоступны"
                    statusDetail.text = e.message ?: "Security endpoint failed."
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { setBusy(false) }
            }
        }
    }

    private fun render(snapshot: SecurityReadModel.PrivacySnapshot) {
        statusHeadline.text = if (snapshot.enabled) "Privacy mode is ON" else "Privacy mode is OFF"
        statusDetail.text = snapshot.detail
        statusPill.text = if (snapshot.enabled) "ON" else "OFF"
        ShellPanelSupport.applyTone(
            statusPill,
            if (snapshot.enabled) "shell-status-tone-success" else "shell-status-tone-muted"
        )
    }

    private fun setBusy(busy: Boolean) {
        enableButton.isDisable = busy
        disableButton.isDisable = busy
        refreshButton.isDisable = busy
    }
}
