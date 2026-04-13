package org.jarvis.desktop.features.home

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.model.VoiceUxStatus
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.jarvis.desktop.shell.ShellRouteContent
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HomeView(
    private val runtimeMonitor: DesktopRuntimeMonitor,
    private val onRefreshRuntime: () -> Unit,
    private val onOpenPlanner: () -> Unit,
    private val onOpenLife: () -> Unit,
    private val onOpenAnalytics: () -> Unit,
    private val onOpenPcControl: () -> Unit,
    private val onOpenSmartHome: () -> Unit,
    private val onOpenVision: (() -> Unit)?,
    private val onOpenVoice: () -> Unit,
    private val onOpenDiagnostics: () -> Unit,
    private val onOpenSettings: () -> Unit
) : ScrollPane(), ShellRouteContent {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private val subtitleLabel = Label()
    private val overallStatusPill = statusPill("Checking Jarvis")
    private val overallHeadlineLabel = Label("Checking Jarvis runtime...").apply {
        styleClass += "home-overall-headline"
        isWrapText = true
    }
    private val overallMetaLabel = metaLabel()

    private val backendStatusPill = statusPill("Backend")
    private val backendDetailLabel = detailLabel()
    private val backendMetaLabel = metaLabel()

    private val voiceStatusPill = statusPill("Voice")
    private val voiceDetailLabel = detailLabel()
    private val voiceMetaLabel = metaLabel()

    private val pcControlStatusPill = statusPill("Desktop actions")
    private val pcControlDetailLabel = detailLabel()
    private val pcControlMetaLabel = metaLabel()

    private val eventsContainer = VBox(12.0).apply {
        styleClass += "home-events-list"
    }

    private val runtimeListener: (DesktopRuntimeMonitor.Snapshot) -> Unit = { snapshot ->
        Platform.runLater { renderSnapshot(snapshot) }
    }
    private val configListener: (ResolvedDesktopConfig) -> Unit = { config ->
        Platform.runLater { renderConfig(config) }
    }

    init {
        styleClass += "shell-route-scroll"
        isFitToWidth = true
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        content = buildContent()

        runtimeMonitor.addListener(runtimeListener)
        AppConfig.addListener(configListener)
    }

    override fun onRouteActivated() {
        renderConfig(AppConfig.current())
        renderSnapshot(runtimeMonitor.currentSnapshot())
    }

    override fun onShellShutdown() {
        runtimeMonitor.removeListener(runtimeListener)
        AppConfig.removeListener(configListener)
    }

    private fun buildContent(): Node {
        val header = VBox(8.0).apply {
            children += Label("Home").apply { styleClass += "shell-page-title" }
            children += subtitleLabel.apply {
                styleClass += "shell-page-subtitle"
                isWrapText = true
            }
        }

        val runtimeOverview = VBox(12.0).apply {
            styleClass += "shell-section-card"
            styleClass += "home-overview-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += VBox(4.0).apply {
                    children += Label("Runtime overview").apply { styleClass += "shell-section-title" }
                    children += Label(
                        "Unified shell status driven by backend, voice, and desktop action channels."
                    ).apply {
                        styleClass += "shell-section-subtitle"
                        isWrapText = true
                    }
                }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                children += overallStatusPill
            }
            children += overallHeadlineLabel
            children += overallMetaLabel
        }

        val statusCards = FlowPane(16.0, 16.0).apply {
            styleClass += "home-status-grid"
            children.addAll(
                statusCard("Backend", backendStatusPill, backendDetailLabel, backendMetaLabel),
                statusCard("Voice", voiceStatusPill, voiceDetailLabel, voiceMetaLabel),
                statusCard("Desktop actions", pcControlStatusPill, pcControlDetailLabel, pcControlMetaLabel)
            )
        }

        val quickActions = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += Label("Quick actions").apply { styleClass += "shell-section-title" }
            children += Label(
                "Use the shell to refresh runtime state or jump straight to the next place you need."
            ).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += FlowPane(12.0, 12.0).apply {
                styleClass += "home-action-grid"
                children.addAll(
                    listOfNotNull(
                        actionButton("Refresh runtime", onRefreshRuntime),
                        actionButton("Open Planner", onOpenPlanner),
                        actionButton("Open Life", onOpenLife),
                        actionButton("Open Analytics", onOpenAnalytics),
                        actionButton("Open PC Control", onOpenPcControl),
                        actionButton("Open Smart Home", onOpenSmartHome),
                        onOpenVision?.let { actionButton("Open Vision Security", it) },
                        actionButton("Open Voice", onOpenVoice),
                        actionButton("Open Diagnostics", onOpenDiagnostics),
                        actionButton("Open Settings", onOpenSettings)
                    )
                )
            }
        }

        val activity = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += Label("Recent activity").apply { styleClass += "shell-section-title" }
            children += Label(
                "Recent runtime, voice, and assistant events collected by the desktop runtime monitor."
            ).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += eventsContainer
        }

        return VBox(18.0).apply {
            styleClass += "shell-home-view"
            padding = Insets(24.0)
            children.addAll(header, runtimeOverview, statusCards, quickActions, activity)
        }
    }

    private fun statusCard(
        title: String,
        pill: Label,
        detail: Label,
        meta: Label
    ): VBox {
        return VBox(12.0).apply {
            styleClass += "shell-section-card"
            styleClass += "home-status-card"
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
            children += meta
        }
    }

    private fun actionButton(text: String, action: () -> Unit): Button {
        return Button(text).apply {
            styleClass += "shell-action-button"
            setOnAction { action() }
        }
    }

    private fun renderConfig(config: ResolvedDesktopConfig) {
        subtitleLabel.text = buildString {
            append("Signed in as ")
            append(TokenManager.getUsername() ?: "offline user")
            append("  |  API ")
            append(config.apiGatewayBaseUrl)
            append("  |  Source ")
            append(config.apiGatewaySource.description)
        }
    }

    private fun renderSnapshot(snapshot: DesktopRuntimeMonitor.Snapshot) {
        val overallState = snapshot.overallState()

        overallStatusPill.text = overallState.displayName()
        applyTone(overallStatusPill, toneFor(overallState))
        overallHeadlineLabel.text = overallHeadline(snapshot)
        overallMetaLabel.text = "Last runtime update ${formatLastUpdated(snapshot)}"

        renderStatus(
            pill = backendStatusPill,
            detail = backendDetailLabel,
            meta = backendMetaLabel,
            status = snapshot.backend,
            detailText = snapshot.backend.detail,
            metaText = "Channel ${snapshot.backend.state.displayName()}  |  Updated ${formatTime(snapshot.backend.updatedAt)}"
        )

        renderStatus(
            pill = voiceStatusPill,
            detail = voiceDetailLabel,
            meta = voiceMetaLabel,
            status = snapshot.voice,
            detailText = describeVoice(snapshot),
            metaText = buildVoiceMeta(snapshot)
        )

        renderStatus(
            pill = pcControlStatusPill,
            detail = pcControlDetailLabel,
            meta = pcControlMetaLabel,
            status = snapshot.pcControl,
            detailText = snapshot.pcControl.detail,
            metaText = "Channel ${snapshot.pcControl.state.displayName()}  |  Updated ${formatTime(snapshot.pcControl.updatedAt)}"
        )

        renderEvents(snapshot.events)
    }

    private fun renderStatus(
        pill: Label,
        detail: Label,
        meta: Label,
        status: DesktopRuntimeMonitor.ConnectionStatus,
        detailText: String,
        metaText: String
    ) {
        pill.text = status.state.displayName()
        applyTone(pill, toneFor(status.state))
        detail.text = detailText
        meta.text = metaText
    }

    private fun renderEvents(events: List<DesktopRuntimeMonitor.RuntimeEvent>) {
        eventsContainer.children.clear()

        if (events.isEmpty()) {
            eventsContainer.children += Label(
                "No assistant activity yet. Use Voice, Diagnostics, or desktop actions to start populating this feed."
            ).apply {
                styleClass += "home-empty-state"
                isWrapText = true
            }
            return
        }

        events.forEach { event ->
            val meta = listOf(
                formatTime(event.timestamp),
                event.source.displayName(),
                event.severity.displayName()
            ).joinToString("  |  ")

            eventsContainer.children += VBox(8.0).apply {
                styleClass += "home-event-card"
                styleClass += toneFor(event.severity)
                children += Label(meta).apply { styleClass += "home-event-meta" }
                children += Label(event.title).apply {
                    styleClass += "home-event-title"
                    isWrapText = true
                }
                if (event.details.isNotBlank()) {
                    children += Label(event.details).apply {
                        styleClass += "home-event-details"
                        isWrapText = true
                    }
                }
            }
        }
    }

    private fun buildVoiceMeta(snapshot: DesktopRuntimeMonitor.Snapshot): String {
        val runtime = snapshot.voiceRuntime
        val parts = mutableListOf(
            "Channel ${snapshot.voice.state.displayName()}",
            "Updated ${formatTime(snapshot.voice.updatedAt)}"
        )

        runtime?.inputDevice?.let { parts += "Mic ${it.name}" }
        runtime?.outputDevice?.let { parts += "Out ${it.name}" }
        VoiceUxStatus.compute(runtime ?: return parts.joinToString("  |  ")).guidance
            ?.takeIf { it.isNotBlank() }
            ?.let { parts += it }

        return parts.joinToString("  |  ")
    }

    private fun describeVoice(snapshot: DesktopRuntimeMonitor.Snapshot): String {
        val runtime = snapshot.voiceRuntime ?: return snapshot.voice.detail
        val status = VoiceUxStatus.compute(runtime)
        return buildList {
            add(status.headline)
            status.guidance?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString("  |  ")
    }

    private fun overallHeadline(snapshot: DesktopRuntimeMonitor.Snapshot): String {
        return when (snapshot.overallState()) {
            DesktopRuntimeMonitor.ConnectionState.CONNECTED ->
                "Ready: runtime, voice, and desktop actions are connected."
            DesktopRuntimeMonitor.ConnectionState.CONNECTING ->
                "Starting up: Jarvis is still connecting."
            DesktopRuntimeMonitor.ConnectionState.DEGRADED ->
                "Degraded: Jarvis is reachable, but one channel needs attention."
            DesktopRuntimeMonitor.ConnectionState.DISCONNECTED ->
                "Disconnected: backend is not currently reachable."
            DesktopRuntimeMonitor.ConnectionState.ERROR ->
                "Attention needed: one or more assistant channels failed."
            DesktopRuntimeMonitor.ConnectionState.UNKNOWN ->
                "Checking Jarvis runtime..."
        }
    }

    private fun formatLastUpdated(snapshot: DesktopRuntimeMonitor.Snapshot): String {
        return listOf(snapshot.backend, snapshot.voice, snapshot.pcControl)
            .maxByOrNull { it.updatedAt }
            ?.updatedAt
            ?.let(::formatTime)
            ?: "n/a"
    }

    private fun formatTime(timestamp: java.time.Instant): String = timeFormatter.format(timestamp)

    private fun statusPill(text: String): Label {
        return Label(text).apply {
            styleClass.addAll("shell-status-pill", "shell-status-tone-muted")
        }
    }

    private fun detailLabel(): Label {
        return Label().apply {
            styleClass += "home-status-detail"
            isWrapText = true
        }
    }

    private fun metaLabel(): Label {
        return Label().apply {
            styleClass += "home-meta-label"
            isWrapText = true
        }
    }

    private fun applyTone(label: Label, toneClass: String) {
        label.styleClass.removeIf {
            it == "shell-status-pill" || it.startsWith("shell-status-tone-")
        }
        label.styleClass.addAll("shell-status-pill", toneClass)
    }

    private fun toneFor(state: DesktopRuntimeMonitor.ConnectionState): String {
        return when (state) {
            DesktopRuntimeMonitor.ConnectionState.CONNECTED -> "shell-status-tone-success"
            DesktopRuntimeMonitor.ConnectionState.CONNECTING -> "shell-status-tone-info"
            DesktopRuntimeMonitor.ConnectionState.DEGRADED -> "shell-status-tone-warning"
            DesktopRuntimeMonitor.ConnectionState.DISCONNECTED,
            DesktopRuntimeMonitor.ConnectionState.ERROR -> "shell-status-tone-error"
            DesktopRuntimeMonitor.ConnectionState.UNKNOWN -> "shell-status-tone-muted"
        }
    }

    private fun toneFor(severity: DesktopRuntimeMonitor.EventSeverity): String {
        return when (severity) {
            DesktopRuntimeMonitor.EventSeverity.INFO -> "home-event-tone-info"
            DesktopRuntimeMonitor.EventSeverity.SUCCESS -> "home-event-tone-success"
            DesktopRuntimeMonitor.EventSeverity.WARNING -> "home-event-tone-warning"
            DesktopRuntimeMonitor.EventSeverity.ERROR -> "home-event-tone-error"
        }
    }

    private fun DesktopRuntimeMonitor.ConnectionState.displayName(): String {
        return name.lowercase().replaceFirstChar(Char::titlecase)
    }

    private fun DesktopRuntimeMonitor.EventSeverity.displayName(): String {
        return name.lowercase().replaceFirstChar(Char::titlecase)
    }

    private fun DesktopRuntimeMonitor.EventSource.displayName(): String {
        return name.lowercase()
            .split("_")
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    }
}
