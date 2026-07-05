package org.jarvis.desktop.features.media

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.features.common.ShellPanelSupport
import org.jarvis.desktop.shell.ShellRouteContent
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media Jobs panel — lists async media-processing jobs (extract-audio,
 * transcribe, Russian subtitles/dub, mux) from media-service, lets the user
 * cancel an in-flight job, and surfaces a download link per artifact.
 */
class MediaJobsView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = MediaJobsReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-media-jobs").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Media")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "Async media jobs — extraction, transcription, Russian subtitles/dub, and mux."
    )
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { loadJobs() }
    }

    private val jobsContainer = VBox(12.0)

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-media-jobs-view"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
        renderPlaceholder("Refresh to load media jobs.")
    }

    override fun onRouteActivated() {
        loadJobs()
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
            children.addAll(header, jobsCard)
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

    private fun renderJobs(jobs: List<MediaJobsReadModel.Job>) {
        if (jobs.isEmpty()) {
            renderPlaceholder("No media jobs yet.")
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
                    styleClass += "shell-section-subtitle"
                }
            }
            if (job.artifacts.isNotEmpty()) {
                children += VBox(4.0).apply {
                    job.artifacts.forEachIndexed { index, artifact ->
                        children += artifactLink(job.id, index, artifact)
                    }
                }
            }
        }
    }

    private fun artifactLink(jobId: String, index: Int, artifact: MediaJobsReadModel.Artifact): Node {
        val label = "${artifact.kind} (${artifact.contentType}, ${artifact.sizeBytes} bytes)"
        return Hyperlink(label).apply {
            setOnAction { openArtifact(jobId, index) }
        }
    }

    private fun openArtifact(jobId: String, index: Int) {
        val url = "${AppConfig.apiBaseUrl}/media/jobs/${jobId}/artifacts/$index"
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                statusLabel.text = "Download link: $url"
            }
        }.onFailure {
            statusLabel.text = "Could not open download link: $url"
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
