package org.jarvis.desktop.features.media

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.features.common.ShellPanelSupport
import org.jarvis.desktop.shell.ShellRouteContent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val PROGRESS_BAR_WIDTH = 140.0

/**
 * Media Jobs panel — lets the user submit async media-processing jobs
 * (probe, Russian subtitles/dub, mux) to media-service, tracks their
 * status/progress, cancels an in-flight job, and downloads an artifact once
 * a job completes. Also surfaces whether media-service is currently running
 * its providers in mock or real/native mode.
 */
class MediaJobsView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = MediaJobsReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-media-jobs").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)
    private val modeInFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Media")
    private val modeBadge = ShellPanelSupport.statusPill("Mode: …")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "Async media jobs — extraction, transcription, Russian subtitles/dub, and mux."
    )
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { loadJobs() }
    }

    private val createForm = MediaJobCreateForm(onSubmit = ::handleCreateJobRequest)

    private val jobsContainer = VBox(12.0)

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-media-jobs-view"
        stylesheets += requireNotNull(javaClass.getResource("/css/media-jobs.css")) {
            "media-jobs.css missing from classpath"
        }.toExternalForm()
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
        renderPlaceholder("Refresh to load media jobs.")
    }

    override fun onRouteActivated() {
        loadJobs()
        loadModeBadge()
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(6.0).apply {
                children += Label("Media Jobs").apply { styleClass += "shell-page-title" }
                children += Label("Track and manage asynchronous media-processing jobs.").apply {
                    styleClass += "shell-page-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += modeBadge
            children += statusPill
            children += refreshButton
        }

        val jobsCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Jobs")
            children += statusLabel
            children += jobsContainer
        }

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, createForm, jobsCard)
        }
    }

    private fun loadJobs() {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        refreshButton.isDisable = true
        statusPill.text = "Loading"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "Loading media jobs…"

        worker.execute {
            try {
                val jobs = readModel.listJobs()
                Platform.runLater {
                    renderJobs(jobs)
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    statusLabel.text = "${jobs.size} job(s)."
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Media request failed."
                    renderPlaceholder("Медиа-сервис временно недоступен.\n${e.message ?: "Unknown error"}")
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { refreshButton.isDisable = false }
            }
        }
    }

    private fun loadModeBadge() {
        if (!modeInFlight.compareAndSet(false, true)) {
            return
        }
        worker.execute {
            try {
                val status = readModel.status()
                Platform.runLater { renderModeBadge(status) }
            } catch (e: Exception) {
                Platform.runLater {
                    modeBadge.text = "Mode: unknown"
                    ShellPanelSupport.applyTone(modeBadge, "shell-status-tone-muted")
                }
            } finally {
                modeInFlight.set(false)
            }
        }
    }

    private fun renderModeBadge(status: MediaJobsReadModel.MediaStatus) {
        modeBadge.text = "Mode: ${status.overallMode}"
        val tone = when (status.overallMode) {
            "MOCK" -> "shell-status-tone-warning"
            "REAL" -> "shell-status-tone-success"
            "MIXED" -> "shell-status-tone-info"
            else -> "shell-status-tone-muted"
        }
        ShellPanelSupport.applyTone(modeBadge, tone)
    }

    private fun handleCreateJobRequest(request: MediaCreateJobRequest) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        createForm.setBusy(true)
        statusPill.text = "Submitting"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")

        worker.execute {
            try {
                when (request) {
                    is MediaCreateJobRequest.Probe -> {
                        val summary = readModel.probe(
                            request.sourcePath,
                            request.preferredLanguage,
                            request.overrideAudioIndex
                        )
                        Platform.runLater {
                            statusPill.text = "Ready"
                            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                            statusLabel.text = formatProbeSummary(summary)
                        }
                    }
                    is MediaCreateJobRequest.Subtitles -> {
                        readModel.createSubtitlesJob(request.sourcePath)
                        reloadAfterCreate()
                    }
                    is MediaCreateJobRequest.Dub -> {
                        readModel.createDubJob(
                            request.sourcePath,
                            request.voiceProfileMode,
                            request.voiceId,
                            request.consentConfirmed
                        )
                        reloadAfterCreate()
                    }
                    is MediaCreateJobRequest.Mux -> {
                        readModel.createMuxJob(
                            request.sourcePath,
                            request.subtitleFile,
                            request.dubAudioFile,
                            request.outputName
                        )
                        reloadAfterCreate()
                    }
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Failed"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to submit job."
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { createForm.setBusy(false) }
            }
        }
    }

    /** Reloads the job list from within the worker thread, right after a create call completes. */
    private fun reloadAfterCreate() {
        val jobs = readModel.listJobs()
        Platform.runLater {
            renderJobs(jobs)
            statusPill.text = "Ready"
            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
            statusLabel.text = "${jobs.size} job(s)."
        }
    }

    private fun formatProbeSummary(summary: MediaJobsReadModel.ProbeSummary): String {
        val duration = summary.durationSeconds?.let { "%.1fs".format(it) } ?: "unknown duration"
        val selected = summary.selectedAudioIndex?.let { "audio stream #$it auto-selected" } ?: "no audio stream auto-selected"
        return "Probe complete — $duration, ${summary.videoStreams} video / ${summary.audioStreams} audio / " +
            "${summary.subtitleStreams} subtitle stream(s), $selected."
    }

    private fun cancelJob(jobId: String) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusPill.text = "Cancelling"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")

        worker.execute {
            try {
                readModel.cancel(jobId)
                val jobs = readModel.listJobs()
                Platform.runLater {
                    inFlight.set(false)
                    renderJobs(jobs)
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to cancel job."
                }
            }
        }
    }

    private fun downloadArtifact(jobId: String, index: Int, artifact: MediaJobsReadModel.Artifact) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusLabel.text = "Downloading ${artifact.kind}…"

        worker.execute {
            try {
                val bytes = readModel.downloadArtifact(jobId, index)
                Platform.runLater {
                    inFlight.set(false)
                    saveArtifactToDisk(jobId, index, artifact, bytes)
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    statusLabel.text = e.message ?: "Failed to download artifact."
                }
            }
        }
    }

    private fun saveArtifactToDisk(jobId: String, index: Int, artifact: MediaJobsReadModel.Artifact, bytes: ByteArray) {
        val chooser = FileChooser().apply {
            title = "Save ${artifact.kind}"
            initialFileName = suggestedFileName(jobId, artifact)
        }
        val target = chooser.showSaveDialog(scene?.window)
        if (target == null) {
            statusLabel.text = "Download ready — save cancelled."
            return
        }
        runCatching { target.writeBytes(bytes) }
            .onSuccess { statusLabel.text = "Saved ${target.name}." }
            .onFailure { statusLabel.text = "Could not write file: ${it.message}" }
    }

    private fun suggestedFileName(jobId: String, artifact: MediaJobsReadModel.Artifact): String {
        val safeKind = artifact.kind.ifBlank { "artifact" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${jobId}_$safeKind${guessExtension(artifact.contentType)}"
    }

    private fun guessExtension(contentType: String): String = when {
        contentType.contains("srt") -> ".srt"
        contentType.contains("json") -> ".json"
        contentType.startsWith("audio/wav") -> ".wav"
        contentType.startsWith("audio/flac") -> ".flac"
        contentType.startsWith("audio/") -> ".audio"
        contentType.startsWith("video/") -> ".mkv"
        else -> ""
    }

    private fun renderJobs(jobs: List<MediaJobsReadModel.Job>) {
        if (jobs.isEmpty()) {
            renderPlaceholder("No media jobs yet. Use Create Job above to submit one.")
            return
        }
        jobsContainer.children.setAll(jobs.map(::jobCard))
    }

    private fun jobCard(job: MediaJobsReadModel.Job): Node {
        return VBox(6.0).apply {
            styleClass += "shell-section-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label("${job.type} · ${job.id}").apply { styleClass += "shell-section-title" }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                children += progressNode(job.status)
                val pill = ShellPanelSupport.statusPill(job.status)
                ShellPanelSupport.applyTone(pill, statusTone(job.status))
                children += pill
                children += Button("Cancel").apply {
                    styleClass += "shell-action-button"
                    isDisable = job.isTerminal
                    setOnAction { cancelJob(job.id) }
                }
            }
            if (job.inputFile.isNotBlank()) {
                children += Label("input: ${job.inputFile}").apply {
                    isWrapText = true
                    styleClass += "shell-section-subtitle"
                }
            }
            job.errorMessage?.takeIf { it.isNotBlank() }?.let {
                children += Label(it).apply {
                    isWrapText = true
                    styleClass += "shell-status-tone-error"
                }
            }
            if (job.artifacts.isNotEmpty()) {
                children += VBox(4.0).apply {
                    job.artifacts.forEachIndexed { index, artifact ->
                        children += artifactRow(job.id, index, artifact)
                    }
                }
            }
        }
    }

    /** Determinate/indeterminate progress by job status — media-service reports no numeric percentage. */
    private fun progressNode(status: String): Node = when (status) {
        "COMPLETED" -> ProgressBar(1.0).apply {
            styleClass += "media-job-progress-bar"
            prefWidth = PROGRESS_BAR_WIDTH
        }
        "RUNNING", "CREATED" -> ProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS).apply {
            styleClass += "media-job-progress-bar"
            prefWidth = PROGRESS_BAR_WIDTH
        }
        else -> Label("—").apply { styleClass += "shell-section-subtitle" }
    }

    private fun artifactRow(jobId: String, index: Int, artifact: MediaJobsReadModel.Artifact): Node {
        val label = "${artifact.kind} (${artifact.contentType}, ${artifact.sizeBytes} bytes)"
        return HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            children += Label(label).apply {
                isWrapText = true
                styleClass += "shell-section-subtitle"
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += Button("Download").apply {
                styleClass += "shell-action-button"
                setOnAction { downloadArtifact(jobId, index, artifact) }
            }
        }
    }

    private fun statusTone(status: String): String = when (status) {
        "COMPLETED" -> "shell-status-tone-success"
        "FAILED", "CANCELLED" -> "shell-status-tone-error"
        "RUNNING" -> "shell-status-tone-info"
        else -> "shell-status-tone-muted"
    }

    private fun renderPlaceholder(message: String) {
        jobsContainer.children.setAll(
            VBox(6.0).apply {
                styleClass.addAll("shell-section-card", "shell-placeholder")
                children += Label(message).apply {
                    styleClass += "shell-placeholder-body"
                    isWrapText = true
                }
            }
        )
    }
}
