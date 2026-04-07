package org.jarvis.desktop.features.vision

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.shell.ShellRouteContent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VisionSecurityView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = VisionSecurityReadModel(apiClient)
    private val refreshExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-vision-security").apply { isDaemon = true }
    }
    private val refreshInFlight = AtomicBoolean(false)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private val updatedLabel = Label("Waiting for vision security snapshot")
    private val refreshButton = Button("Refresh")
    private val feedbackPill = statusPill("Vision Security")
    private val feedbackLabel = secondaryLabel(
        "Local owner verification, incident capture, and report-ready pipeline export for the Ubuntu desktop path."
    )

    private val serviceStatusPill = statusPill("Checking")
    private val serviceHeadline = summaryHeadlineLabel()
    private val serviceDetail = summaryDetailLabel()

    private val monitoringValue = valueLabel()
    private val ownerValue = valueLabel()
    private val lastDecisionValue = valueLabel()
    private val unknownStreakValue = valueLabel()
    private val incidentCountValue = valueLabel()
    private val activeUserValue = codeValueLabel()

    private val cameraPill = statusPill("Camera")
    private val cameraDetail = secondaryLabel()
    private val screenshotPill = statusPill("Screenshot")
    private val screenshotDetail = secondaryLabel()
    private val ocrPill = statusPill("OCR")
    private val ocrDetail = secondaryLabel()
    private val emailPill = statusPill("Email")
    private val emailDetail = secondaryLabel()
    private val gpuPill = statusPill("GPU")
    private val gpuDetail = secondaryLabel()

    private val intervalValue = codeValueLabel()
    private val debounceValue = valueLabel()
    private val cooldownValue = valueLabel()
    private val storageValue = codeValueLabel()
    private val recipientValue = codeValueLabel()
    private val ocrLanguageValue = codeValueLabel()
    private val displayServerValue = valueLabel()
    private val gpuPreferenceValue = valueLabel()

    private val incidentsContainer = VBox(10.0).apply {
        styleClass += "vision-incident-list"
    }

    private var refreshTask: ScheduledFuture<*>? = null

    init {
        styleClass += "shell-route-scroll"
        isFitToWidth = true
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        content = buildContent()
        refreshButton.setOnAction { refreshNow() }
    }

    override fun onRouteActivated() {
        refreshNow()
        startAutoRefresh()
    }

    override fun onRouteDeactivated() {
        stopAutoRefresh()
    }

    override fun onShellShutdown() {
        stopAutoRefresh()
        refreshExecutor.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(6.0).apply {
                children += Label("Vision Security").apply { styleClass += "shell-page-title" }
                children += Label(
                    "Owner-vs-unknown face verification, incident evidence capture, and classical CV stage export."
                ).apply {
                    styleClass += "shell-page-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += updatedLabel.apply { styleClass += "diagnostics-updated-label" }
            children += refreshButton.apply { styleClass += "shell-action-button" }
        }

        val feedbackRow = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += feedbackPill
            children += feedbackLabel
        }

        val summary = VBox(12.0).apply {
            styleClass += "shell-section-card"
            styleClass += "vision-summary-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += VBox(4.0).apply {
                    children += Label("Runtime status").apply { styleClass += "shell-section-title" }
                    children += Label(
                        "Monitoring evaluates webcam frames every two seconds when enabled and debounces unknown detections before incident creation."
                    ).apply {
                        styleClass += "shell-section-subtitle"
                        isWrapText = true
                    }
                }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                children += serviceStatusPill
            }
            children += serviceHeadline
            children += serviceDetail
        }

        val statusGrid = FlowPane(16.0, 16.0).apply {
            styleClass += "vision-metric-grid"
            children.addAll(
                infoCard("Monitoring", monitoringValue),
                infoCard("Owner enrolled", ownerValue),
                infoCard("Last decision", lastDecisionValue),
                infoCard("Unknown streak", unknownStreakValue),
                infoCard("Incidents", incidentCountValue),
                infoCard("Active user", activeUserValue)
            )
        }

        val capabilityGrid = FlowPane(16.0, 16.0).apply {
            styleClass += "vision-capability-grid"
            children.addAll(
                capabilityCard("Camera", cameraPill, cameraDetail),
                capabilityCard("Screenshot", screenshotPill, screenshotDetail),
                capabilityCard("OCR", ocrPill, ocrDetail),
                capabilityCard("Email", emailPill, emailDetail),
                capabilityCard("GPU", gpuPill, gpuDetail)
            )
        }

        val controls = sectionCard(
            title = "Controls",
            subtitle = "Start or pause monitoring, refresh owner enrollment, export the classical CV stages, and verify alert delivery.",
            body = FlowPane(12.0, 12.0).apply {
                children.addAll(
                    actionButton("Start Monitoring") {
                        executeAction { readModel.startMonitoring() }
                    },
                    actionButton("Stop Monitoring") {
                        executeAction { readModel.stopMonitoring() }
                    },
                    actionButton("Capture Owner Enrollment") {
                        executeAction { readModel.captureEnrollment() }
                    },
                    actionButton("Reset Enrollment") {
                        executeAction { readModel.resetEnrollment() }
                    },
                    actionButton("Export Pipeline Snapshot") {
                        executeAction { readModel.capturePipelineSnapshot() }
                    },
                    actionButton("Send Test Alert") {
                        executeAction { readModel.sendTestAlert() }
                    }
                )
            }
        )

        val configGrid = GridPane().apply {
            hgap = 16.0
            vgap = 12.0
            add(settingLabel("Check interval"), 0, 0)
            add(intervalValue, 1, 0)
            add(settingLabel("Debounce frames"), 0, 1)
            add(debounceValue, 1, 1)
            add(settingLabel("Cooldown"), 0, 2)
            add(cooldownValue, 1, 2)
            add(settingLabel("Storage root"), 0, 3)
            add(storageValue, 1, 3)
            add(settingLabel("Alert recipient"), 0, 4)
            add(recipientValue, 1, 4)
            add(settingLabel("OCR language"), 0, 5)
            add(ocrLanguageValue, 1, 5)
            add(settingLabel("Display server"), 0, 6)
            add(displayServerValue, 1, 6)
            add(settingLabel("GPU preference"), 0, 7)
            add(gpuPreferenceValue, 1, 7)
        }

        val configSection = sectionCard(
            title = "Configuration",
            subtitle = "Read-only summary of the thresholds, storage path, OCR mode, and platform assumptions that currently drive the service.",
            body = configGrid
        )

        val incidentsSection = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += Label("Recent incidents").apply { styleClass += "shell-section-title" }
            children += Label(
                "Each record shows the last confirmed alert with local evidence paths for webcam, screenshot, OCR, and pipeline-stage exports."
            ).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += incidentsContainer
        }

        return VBox(18.0).apply {
            styleClass += "shell-vision-view"
            padding = Insets(24.0)
            children.addAll(header, feedbackRow, summary, statusGrid, capabilityGrid, controls, configSection, incidentsSection)
        }
    }

    private fun refreshNow() {
        if (!refreshInFlight.compareAndSet(false, true)) {
            return
        }

        Platform.runLater {
            refreshButton.isDisable = true
            updatedLabel.text = "Refreshing vision security..."
        }

        refreshExecutor.execute {
            try {
                val snapshot = readModel.refresh()
                Platform.runLater { render(snapshot) }
            } catch (e: Exception) {
                Platform.runLater { renderFailure(e) }
            } finally {
                refreshInFlight.set(false)
                Platform.runLater { refreshButton.isDisable = false }
            }
        }
    }

    private fun startAutoRefresh() {
        stopAutoRefresh()
        refreshTask = refreshExecutor.scheduleAtFixedRate(
            { refreshNow() },
            10,
            10,
            TimeUnit.SECONDS
        )
    }

    private fun stopAutoRefresh() {
        refreshTask?.cancel(false)
        refreshTask = null
    }

    private fun render(snapshot: VisionSecurityReadModel.Snapshot) {
        updatedLabel.text = "Updated ${timeFormatter.format(snapshot.refreshedAt)}"
        renderStatus(snapshot.status)
        renderIncidents(snapshot.incidents)
    }

    private fun renderStatus(status: VisionSecurityReadModel.StatusSnapshot) {
        feedbackLabel.text = status.lastReason
        serviceStatusPill.text = status.serviceStatus
        applyTone(serviceStatusPill, toneForService(status.serviceStatus))

        serviceHeadline.text = buildString {
            append(if (status.monitoringEnabled) "Monitoring is active" else "Monitoring is paused")
            append("  |  Owner enrolled: ")
            append(if (status.ownerEnrolled) "yes" else "no")
            append("  |  Last decision: ")
            append(status.lastDecision ?: "n/a")
        }
        serviceDetail.text = status.lastReason

        monitoringValue.text = if (status.monitoringEnabled) "Enabled" else "Disabled"
        ownerValue.text = if (status.ownerEnrolled) "Enrolled" else "Not enrolled"
        lastDecisionValue.text = status.lastDecision ?: "n/a"
        unknownStreakValue.text = status.unknownStreak.toString()
        incidentCountValue.text = status.incidentCount.toString()
        activeUserValue.text = status.activeUserId ?: "n/a"

        renderCapability(cameraPill, cameraDetail, status.camera)
        renderCapability(screenshotPill, screenshotDetail, status.screenshot)
        renderCapability(ocrPill, ocrDetail, status.ocr)
        renderCapability(emailPill, emailDetail, status.email)
        gpuPill.text = if (status.gpu.available) "GPU visible" else "CPU baseline"
        applyTone(gpuPill, if (status.gpu.available) "shell-status-tone-info" else "shell-status-tone-muted")
        gpuDetail.text = status.gpu.detail

        intervalValue.text = "${status.config.checkIntervalMs} ms"
        debounceValue.text = status.config.debounceUnknownFrames.toString()
        cooldownValue.text = "${status.config.alertCooldownSeconds} s"
        storageValue.text = status.config.storageRoot
        recipientValue.text = status.config.emailRecipient.ifBlank { "not configured" }
        ocrLanguageValue.text = status.config.ocrLanguage
        displayServerValue.text = status.config.displayServer
        gpuPreferenceValue.text = if (status.config.preferGpu) "Prefer GPU when possible" else "CPU baseline preferred"
    }

    private fun renderIncidents(incidents: List<VisionSecurityReadModel.IncidentSnapshot>) {
        incidentsContainer.children.clear()
        if (incidents.isEmpty()) {
            incidentsContainer.children += Label(
                "No incidents yet. Start monitoring, keep the owner enrolled, and use the pipeline snapshot action to generate report assets without waiting for an alert."
            ).apply {
                styleClass += "home-empty-state"
                isWrapText = true
            }
            return
        }

        incidents.forEach { incident ->
            incidentsContainer.children += VBox(8.0).apply {
                styleClass += "vision-incident-card"
                children += HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children += Label(incident.decision).apply { styleClass += "shell-section-title" }
                    children += Label(incident.createdAt).apply { styleClass += "shell-section-subtitle" }
                }
                children += Label(incident.reason).apply {
                    styleClass += "settings-meta-label"
                    isWrapText = true
                }
                children += Label("Tags: ${incident.tags.joinToString().ifBlank { "none" }}").apply {
                    styleClass += "shell-section-subtitle"
                    isWrapText = true
                }
                children += Label("Window: ${incident.activeWindowTitle.ifBlank { "n/a" }}").apply {
                    styleClass += "shell-section-subtitle"
                    isWrapText = true
                }
                children += Label("Process: ${incident.activeProcessName.ifBlank { "n/a" }}").apply {
                    styleClass += "shell-section-subtitle"
                    isWrapText = true
                }
                children += Label("Evidence: ${incident.incidentDirectory}").apply {
                    styleClass += "vision-path-label"
                    isWrapText = true
                }
                children += Label("Webcam: ${incident.webcamPhotoPath.ifBlank { "n/a" }}").apply {
                    styleClass += "vision-path-label"
                    isWrapText = true
                }
                children += Label("Screenshot: ${incident.screenshotPath.ifBlank { "n/a" }}").apply {
                    styleClass += "vision-path-label"
                    isWrapText = true
                }
            }
        }
    }

    private fun executeAction(action: () -> VisionSecurityReadModel.ActionResult) {
        refreshExecutor.execute {
            try {
                val result = action()
                Platform.runLater {
                    feedbackPill.text = result.headline
                    applyTone(feedbackPill, "shell-status-tone-info")
                    feedbackLabel.text = result.detail
                }
                refreshNow()
            } catch (e: Exception) {
                Platform.runLater {
                    feedbackPill.text = "Action failed"
                    applyTone(feedbackPill, "shell-status-tone-error")
                    feedbackLabel.text = e.message ?: "Vision security action failed"
                }
            }
        }
    }

    private fun renderFailure(error: Exception) {
        updatedLabel.text = "Vision security refresh failed"
        feedbackPill.text = "Unavailable"
        applyTone(feedbackPill, "shell-status-tone-error")
        feedbackLabel.text = error.message ?: "Vision security API is unavailable"
    }

    private fun renderCapability(pill: Label, detail: Label, snapshot: VisionSecurityReadModel.CapabilitySnapshot) {
        pill.text = snapshot.state
        applyTone(pill, toneForCapability(snapshot.state))
        detail.text = snapshot.detail
    }

    private fun sectionCard(title: String, subtitle: String, body: Node): VBox {
        return VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += Label(title).apply { styleClass += "shell-section-title" }
            children += Label(subtitle).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += body
        }
    }

    private fun capabilityCard(title: String, pill: Label, detail: Label): VBox {
        return VBox(10.0).apply {
            styleClass += "shell-section-card"
            styleClass += "vision-capability-card"
            prefWidth = 300.0
            minWidth = 260.0
            maxWidth = Double.MAX_VALUE
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label(title).apply { styleClass += "shell-section-title" }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                children += pill
            }
            children += detail
        }
    }

    private fun infoCard(title: String, value: Label): VBox {
        return VBox(8.0).apply {
            styleClass += "shell-section-card"
            styleClass += "vision-info-card"
            prefWidth = 220.0
            minWidth = 200.0
            maxWidth = Double.MAX_VALUE
            children += Label(title).apply { styleClass += "shell-section-title" }
            children += value
        }
    }

    private fun actionButton(text: String, action: () -> Unit): Button {
        return Button(text).apply {
            styleClass += "shell-action-button"
            setOnAction { action() }
        }
    }

    private fun settingLabel(text: String): Label {
        return Label(text).apply {
            styleClass += "settings-field-label"
            isWrapText = true
        }
    }

    private fun statusPill(text: String): Label {
        return Label(text).apply {
            styleClass += "shell-status-pill"
            styleClass += "shell-status-tone-muted"
        }
    }

    private fun summaryHeadlineLabel(): Label {
        return Label().apply {
            styleClass += "settings-value-label"
            isWrapText = true
        }
    }

    private fun summaryDetailLabel(): Label {
        return Label().apply {
            styleClass += "settings-meta-label"
            isWrapText = true
        }
    }

    private fun secondaryLabel(initial: String = ""): Label {
        return Label(initial).apply {
            styleClass += "settings-meta-label"
            isWrapText = true
        }
    }

    private fun valueLabel(): Label {
        return Label().apply {
            styleClass += "settings-value-label"
            isWrapText = true
        }
    }

    private fun codeValueLabel(): Label {
        return Label().apply {
            styleClass += "diagnostics-code-value"
            isWrapText = true
        }
    }

    private fun applyTone(label: Label, toneClass: String) {
        label.styleClass.removeAll(
            "shell-status-tone-muted",
            "shell-status-tone-info",
            "shell-status-tone-success",
            "shell-status-tone-warning",
            "shell-status-tone-error"
        )
        label.styleClass += toneClass
    }

    private fun toneForService(status: String): String = when (status.uppercase()) {
        "READY" -> "shell-status-tone-success"
        "DEGRADED" -> "shell-status-tone-warning"
        "UNAVAILABLE" -> "shell-status-tone-error"
        else -> "shell-status-tone-muted"
    }

    private fun toneForCapability(status: String): String = when (status.uppercase()) {
        "AVAILABLE", "READY" -> "shell-status-tone-success"
        "DEGRADED", "LIMITED" -> "shell-status-tone-warning"
        "UNAVAILABLE", "FAILED" -> "shell-status-tone-error"
        else -> "shell-status-tone-muted"
    }
}
