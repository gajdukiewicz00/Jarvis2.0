package org.jarvis.desktop.features.ai

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
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.shell.ShellRouteContent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AiView(
    private val readModel: AiReadModel = AiReadModel(tokenProvider = { TokenManager.getAccessToken() })
) : ScrollPane(), ShellRouteContent {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-ai").apply { isDaemon = true }
    }
    private val actionInFlight = AtomicBoolean(false)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private val overallStatusPill = statusPill("Checking AI")
    private val overallHeadlineLabel = Label("Checking AI runtime...").apply {
        styleClass += "ai-overall-headline"
        isWrapText = true
    }
    private val overallDetailLabel = Label().apply {
        styleClass += "ai-overall-detail"
        isWrapText = true
    }
    private val overallMetaLabel = metaLabel()

    private val feedbackPill = statusPill("AI")
    private val feedbackLabel = Label("Use the controls below to manage the local AI runtime.").apply {
        styleClass += "ai-feedback-text"
        isWrapText = true
    }

    private val llmStatusPill = statusPill("LLM")
    private val llmProviderLabel = detailLabel()
    private val llmBaseUrlLabel = codeLabel()
    private val llmReasonLabel = metaLabel()

    private val memoryStatusPill = statusPill("Memory")
    private val memoryDetailLabel = detailLabel()
    private val memoryReasonLabel = metaLabel()

    private val embeddingStatusPill = statusPill("Embedding")
    private val embeddingModelLabel = detailLabel()
    private val embeddingReasonLabel = metaLabel()

    private val gpuStatusPill = statusPill("GPU")
    private val gpuDeviceLabel = detailLabel()
    private val gpuLayersLabel = metaLabel()
    private val gpuNameLabel = metaLabel()
    private val gpuReadinessLabel = metaLabel()

    private val lifecycleStatusPill = statusPill("Lifecycle")
    private val lifecycleStateLabel = detailLabel()
    private val lifecycleReasonLabel = metaLabel()
    private val lifecycleWarmupLabel = metaLabel()

    private val admissionActiveLabel = detailLabel()
    private val admissionQueueLabel = detailLabel()
    private val admissionStatsLabel = metaLabel()

    private val modelLlmLabel = detailLabel()
    private val modelEffectiveLabel = codeLabel()
    private val modelEmbeddingLabel = detailLabel()
    private val modelProviderLabel = detailLabel()
    private val modelStackLabel = codeLabel()

    private val configLlmLabel = detailLabel()
    private val configMemoryLabel = detailLabel()
    private val configGpuModeLabel = detailLabel()

    private val refreshButton = Button("Refresh status").apply { styleClass += "shell-action-button" }
    private val startButton = Button("Start AI").apply { styleClass += "shell-action-button" }
    private val stopButton = Button("Stop AI").apply { styleClass += "shell-action-button-danger" }
    private val restartButton = Button("Restart AI").apply { styleClass += "shell-action-button" }

    init {
        styleClass += "shell-route-scroll"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()

        refreshButton.setOnAction { refreshAi() }
        startButton.setOnAction { runAction("start") }
        stopButton.setOnAction { runAction("stop") }
        restartButton.setOnAction { runAction("restart") }
    }

    override fun onRouteActivated() {
        refreshAi()
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(16.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(8.0).apply {
                children += Label("AI Runtime").apply { styleClass += "shell-page-title" }
                children += Label(
                    "Monitor and control the local AI inference stack: LLM, embedding, memory, and GPU acceleration."
                ).apply {
                    styleClass += "shell-page-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += overallStatusPill
        }

        val feedbackRow = HBox(12.0).apply {
            styleClass += "ai-feedback-row"
            alignment = Pos.CENTER_LEFT
            children += feedbackPill
            children += feedbackLabel
        }

        val overallCard = VBox(12.0).apply {
            styleClass.addAll("shell-section-card", "ai-overall-card")
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += VBox(4.0).apply {
                    children += Label("AI stack overview").apply { styleClass += "shell-section-title" }
                    children += Label(
                        "Aggregated status from llm-service, llm-server, embedding-service, and memory-service."
                    ).apply {
                        styleClass += "shell-section-subtitle"
                        isWrapText = true
                    }
                }
            }
            children += overallHeadlineLabel
            children += overallDetailLabel
            children += overallMetaLabel
        }

        val statusCards = FlowPane(16.0, 16.0).apply {
            styleClass += "ai-status-grid"
            children.addAll(
                llmCard(),
                inferenceCard(),
                memoryCard(),
                embeddingCard(),
                gpuCard()
            )
        }

        val infoCards = FlowPane(16.0, 16.0).apply {
            styleClass += "ai-status-grid"
            children.addAll(
                modelCard(),
                configCard()
            )
        }

        val actionsSection = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += Label("Actions").apply { styleClass += "shell-section-title" }
            children += Label(
                "Start, stop, or restart the local AI services. Scripts run in the background; refresh to see updated status."
            ).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += FlowPane(12.0, 12.0).apply {
                styleClass += "ai-action-grid"
                children.addAll(refreshButton, startButton, stopButton, restartButton)
            }
        }

        return VBox(18.0).apply {
            styleClass += "shell-ai-view"
            padding = Insets(24.0)
            children.addAll(header, feedbackRow, overallCard, statusCards, infoCards, actionsSection)
        }
    }

    private fun llmCard(): VBox {
        return statusCardTemplate(
            "LLM inference",
            llmStatusPill,
            VBox(8.0).apply {
                children += fieldRow("Provider", llmProviderLabel)
                children += fieldRow("Endpoint", llmBaseUrlLabel)
                children += llmReasonLabel
            }
        )
    }

    private fun inferenceCard(): VBox {
        return statusCardTemplate(
            "Inference pipeline",
            lifecycleStatusPill,
            VBox(8.0).apply {
                children += fieldRow("State", lifecycleStateLabel)
                children += lifecycleReasonLabel
                children += lifecycleWarmupLabel
                children += fieldRow("Active", admissionActiveLabel)
                children += fieldRow("Queue", admissionQueueLabel)
                children += admissionStatsLabel
            }
        )
    }

    private fun memoryCard(): VBox {
        return statusCardTemplate(
            "Long-term memory",
            memoryStatusPill,
            VBox(8.0).apply {
                children += memoryDetailLabel
                children += memoryReasonLabel
            }
        )
    }

    private fun embeddingCard(): VBox {
        return statusCardTemplate(
            "Embedding",
            embeddingStatusPill,
            VBox(8.0).apply {
                children += fieldRow("Model", embeddingModelLabel)
                children += embeddingReasonLabel
            }
        )
    }

    private fun gpuCard(): VBox {
        return statusCardTemplate(
            "GPU acceleration",
            gpuStatusPill,
            VBox(8.0).apply {
                children += fieldRow("Device", gpuDeviceLabel)
                children += gpuLayersLabel
                children += gpuNameLabel
                children += gpuReadinessLabel
            }
        )
    }

    private fun modelCard(): VBox {
        val grid = formGrid().apply {
            add(settingLabel("LLM model"), 0, 0)
            add(modelLlmLabel, 1, 0)
            add(settingLabel("Effective model"), 0, 1)
            add(modelEffectiveLabel, 1, 1)
            add(settingLabel("Embedding model"), 0, 2)
            add(modelEmbeddingLabel, 1, 2)
            add(settingLabel("Provider"), 0, 3)
            add(modelProviderLabel, 1, 3)
            add(settingLabel("Stack ID"), 0, 4)
            add(modelStackLabel, 1, 4)
        }

        return sectionCard(
            title = "Model and stack",
            subtitle = "Configured and effective model identifiers for the local AI runtime.",
            body = grid
        )
    }

    private fun configCard(): VBox {
        val grid = formGrid().apply {
            add(settingLabel("LLM enabled"), 0, 0)
            add(configLlmLabel, 1, 0)
            add(settingLabel("Memory enabled"), 0, 1)
            add(configMemoryLabel, 1, 1)
            add(settingLabel("GPU mode"), 0, 2)
            add(configGpuModeLabel, 1, 2)
        }

        return sectionCard(
            title = "Configuration",
            subtitle = "AI runtime configuration from launcher settings. Change via launcher or ~/.jarvis/config/launcher.properties.",
            body = grid
        )
    }

    private fun statusCardTemplate(title: String, pill: Label, body: Node): VBox {
        return VBox(12.0).apply {
            styleClass.addAll("shell-section-card", "ai-status-card")
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
            children += body
        }
    }

    private fun sectionCard(title: String, subtitle: String, body: Node): VBox {
        return VBox(12.0).apply {
            styleClass.addAll("shell-section-card", "ai-info-card")
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

    private fun fieldRow(label: String, value: Label): HBox {
        return HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            children += Label(label).apply { styleClass += "ai-field-label" }
            children += value
        }
    }

    private fun formGrid(): GridPane {
        return GridPane().apply {
            styleClass += "ai-form-grid"
            hgap = 16.0
            vgap = 12.0
        }
    }

    private fun settingLabel(text: String): Label {
        return Label(text).apply { styleClass += "ai-field-label" }
    }

    private fun refreshAi() {
        if (!actionInFlight.compareAndSet(false, true)) {
            return
        }

        refreshButton.isDisable = true
        showFeedback("Refreshing", "Querying AI runtime endpoints...", "shell-status-tone-info")

        worker.execute {
            try {
                val snapshot = readModel.refresh()
                Platform.runLater {
                    renderSnapshot(snapshot)
                    showFeedback(
                        "Status updated",
                        "AI runtime refreshed at ${timeFormatter.format(snapshot.refreshedAt)}.",
                        "shell-status-tone-success"
                    )
                }
            } catch (e: Exception) {
                Platform.runLater {
                    showFeedback(
                        "Refresh failed",
                        e.message ?: "Could not query AI runtime status.",
                        "shell-status-tone-error"
                    )
                }
            } finally {
                actionInFlight.set(false)
                Platform.runLater { refreshButton.isDisable = false }
            }
        }
    }

    private fun runAction(action: String) {
        if (!actionInFlight.compareAndSet(false, true)) {
            return
        }

        setActionButtonsDisabled(true)
        val displayAction = action.replaceFirstChar(Char::titlecase)
        showFeedback("${displayAction}ing AI", "Running AI lifecycle script...", "shell-status-tone-info")

        worker.execute {
            try {
                val result = when (action) {
                    "start" -> readModel.startAi()
                    "stop" -> readModel.stopAi()
                    "restart" -> readModel.restartAi()
                    else -> AiReadModel.ActionResult("Unknown action", "Action '$action' is not recognized.", false)
                }

                Platform.runLater {
                    showFeedback(
                        result.headline,
                        result.detail,
                        if (result.success) "shell-status-tone-success" else "shell-status-tone-error"
                    )
                }

                if (result.success && action != "stop") {
                    Thread.sleep(3000)
                    try {
                        val snapshot = readModel.refresh()
                        Platform.runLater { renderSnapshot(snapshot) }
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                Platform.runLater {
                    showFeedback(
                        "$displayAction failed",
                        e.message ?: "Unexpected error during $action.",
                        "shell-status-tone-error"
                    )
                }
            } finally {
                actionInFlight.set(false)
                Platform.runLater { setActionButtonsDisabled(false) }
            }
        }
    }

    private fun renderSnapshot(snapshot: AiReadModel.Snapshot) {
        renderOverall(snapshot)
        renderLlm(snapshot.llm)
        renderInference(snapshot.lifecycle, snapshot.admission)
        renderMemory(snapshot.memory)
        renderEmbedding(snapshot.embedding)
        renderGpu(snapshot.gpu)
        renderModel(snapshot.model)
        renderConfig(snapshot.config)
    }

    private fun renderOverall(snapshot: AiReadModel.Snapshot) {
        overallStatusPill.text = snapshot.overallStatus.displayName()
        applyTone(overallStatusPill, toneForAiStatus(snapshot.overallStatus))
        overallHeadlineLabel.text = snapshot.overallReason
        overallDetailLabel.text = "Runtime status: ${snapshot.runtimeRaw}"
        overallMetaLabel.text = "Refreshed at ${timeFormatter.format(snapshot.refreshedAt)}"
    }

    private fun renderLlm(llm: AiReadModel.LlmStatus) {
        llmStatusPill.text = if (llm.available) "Ready" else if (llm.enabled) "Down" else "Disabled"
        applyTone(llmStatusPill, when {
            llm.available -> "shell-status-tone-success"
            !llm.enabled -> "shell-status-tone-muted"
            else -> "shell-status-tone-error"
        })
        llmProviderLabel.text = llm.provider
        llmBaseUrlLabel.text = llm.baseUrl.ifBlank { "Not configured" }
        llmReasonLabel.text = llm.reason.ifBlank { if (llm.available) "Healthy" else "No details" }
    }

    private fun renderInference(lifecycle: AiReadModel.LifecycleStatus, admission: AiReadModel.AdmissionStatus) {
        val ready = lifecycle.state == "READY"
        val usable = lifecycle.usable
        lifecycleStatusPill.text = when {
            ready -> "Ready"
            usable -> "Degraded"
            lifecycle.state == "WARMING_UP" -> "Warming up"
            lifecycle.state == "STARTING" -> "Starting"
            else -> lifecycle.state.lowercase().replaceFirstChar(Char::titlecase)
        }
        applyTone(lifecycleStatusPill, when {
            ready -> "shell-status-tone-success"
            usable -> "shell-status-tone-warning"
            lifecycle.state == "WARMING_UP" || lifecycle.state == "STARTING" -> "shell-status-tone-info"
            else -> "shell-status-tone-error"
        })
        lifecycleStateLabel.text = lifecycle.state
        lifecycleReasonLabel.text = lifecycle.reason.ifBlank { "No details" }
        lifecycleWarmupLabel.text = "Warmup: ${if (lifecycle.warmupComplete) "complete" else "pending"}"
        admissionActiveLabel.text = "${admission.activeInferences} inference(s)"
        admissionQueueLabel.text = "${admission.queueDepth} queued  |  ${admission.availablePermits} permits free"
        admissionStatsLabel.text = "Admitted: ${admission.totalAdmitted}  |  Rejected: ${admission.rejectedCount}"
    }

    private fun renderMemory(memory: AiReadModel.MemoryStatus) {
        memoryStatusPill.text = when {
            memory.available -> "Ready"
            !memory.enabled -> "Disabled"
            !memory.serviceEnabled -> "Disabled"
            else -> "Down"
        }
        applyTone(memoryStatusPill, when {
            memory.available -> "shell-status-tone-success"
            !memory.enabled || !memory.serviceEnabled -> "shell-status-tone-muted"
            else -> "shell-status-tone-error"
        })
        memoryDetailLabel.text = "Status: ${memory.status}  |  Service: ${if (memory.serviceEnabled) "enabled" else "disabled"}"
        memoryReasonLabel.text = memory.reason.ifBlank { if (memory.available) "Healthy" else "No details" }
    }

    private fun renderEmbedding(embedding: AiReadModel.EmbeddingStatus) {
        embeddingStatusPill.text = if (embedding.available) "Ready" else "Down"
        applyTone(embeddingStatusPill, if (embedding.available) "shell-status-tone-success" else "shell-status-tone-error")
        embeddingModelLabel.text = buildString {
            append(embedding.model)
            embedding.dimension?.let { append("  (dim=$it)") }
        }
        embeddingReasonLabel.text = embedding.reason.ifBlank { if (embedding.available) "Healthy" else "No details" }
    }

    private fun renderGpu(gpu: AiReadModel.GpuStatus) {
        gpuStatusPill.text = when {
            gpu.available -> "Active"
            gpu.readinessStatus == "verified" -> "Verified"
            gpu.device == "cpu" || gpu.device == "n/a" -> "CPU only"
            else -> "Unavailable"
        }
        applyTone(gpuStatusPill, when {
            gpu.available -> "shell-status-tone-success"
            gpu.readinessStatus == "verified" -> "shell-status-tone-success"
            gpu.device == "cpu" -> "shell-status-tone-muted"
            else -> "shell-status-tone-warning"
        })
        gpuDeviceLabel.text = gpu.device.ifBlank { "Not detected" }
        gpuLayersLabel.text = buildString {
            append("Configured layers: ${gpu.configuredGpuLayers ?: "n/a"}")
            append("  |  Effective: ${gpu.effectiveGpuLayers ?: "n/a"}")
        }
        gpuNameLabel.text = buildString {
            if (gpu.gpuName.isNotBlank()) append(gpu.gpuName)
            if (gpu.cudaVersion.isNotBlank()) append("  |  CUDA ${gpu.cudaVersion}")
            if (gpu.driverVersion.isNotBlank()) append("  |  Driver ${gpu.driverVersion}")
            if (isEmpty()) append("No GPU hardware detected")
        }
        gpuReadinessLabel.text = "${gpu.readinessStatus}: ${gpu.readinessReason}".take(200)
    }

    private fun renderModel(model: AiReadModel.ModelInfo) {
        modelLlmLabel.text = model.llmModel
        modelEffectiveLabel.text = model.effectiveLlmModel
        modelEmbeddingLabel.text = model.embeddingModel
        modelProviderLabel.text = model.provider
        modelStackLabel.text = model.stackId.ifBlank { "Not resolved" }
    }

    private fun renderConfig(config: AiReadModel.AiConfig) {
        configLlmLabel.text = if (config.llmEnabled) "Enabled" else "Disabled"
        configMemoryLabel.text = if (config.memoryEnabled) "Enabled" else "Disabled"
        configGpuModeLabel.text = config.gpuMode
    }

    private fun showFeedback(headline: String, message: String, toneClass: String) {
        feedbackPill.text = headline
        applyTone(feedbackPill, toneClass)
        feedbackLabel.text = message
    }

    private fun setActionButtonsDisabled(disabled: Boolean) {
        refreshButton.isDisable = disabled
        startButton.isDisable = disabled
        stopButton.isDisable = disabled
        restartButton.isDisable = disabled
    }

    private fun statusPill(text: String): Label {
        return Label(text).apply {
            styleClass.addAll("shell-status-pill", "shell-status-tone-muted")
        }
    }

    private fun detailLabel(): Label {
        return Label().apply {
            styleClass += "ai-detail-label"
            isWrapText = true
        }
    }

    private fun codeLabel(): Label {
        return Label().apply {
            styleClass.addAll("ai-detail-label", "ai-code-label")
            isWrapText = true
        }
    }

    private fun metaLabel(): Label {
        return Label().apply {
            styleClass += "ai-meta-label"
            isWrapText = true
        }
    }

    private fun applyTone(label: Label, toneClass: String) {
        label.styleClass.removeIf { it == "shell-status-pill" || it.startsWith("shell-status-tone-") }
        label.styleClass.addAll("shell-status-pill", toneClass)
    }

    private fun toneForAiStatus(status: AiReadModel.AiStatus): String {
        return when (status) {
            AiReadModel.AiStatus.READY -> "shell-status-tone-success"
            AiReadModel.AiStatus.STARTING -> "shell-status-tone-info"
            AiReadModel.AiStatus.DEGRADED -> "shell-status-tone-warning"
            AiReadModel.AiStatus.DOWN, AiReadModel.AiStatus.ERROR -> "shell-status-tone-error"
            AiReadModel.AiStatus.DISABLED -> "shell-status-tone-muted"
        }
    }

    private fun AiReadModel.AiStatus.displayName(): String {
        return name.lowercase().replaceFirstChar(Char::titlecase)
    }
}
