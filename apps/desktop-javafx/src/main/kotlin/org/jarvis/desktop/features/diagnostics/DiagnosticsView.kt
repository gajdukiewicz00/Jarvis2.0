package org.jarvis.desktop.features.diagnostics

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.features.status.StatusLevel
import org.jarvis.desktop.shell.ShellRouteContent
import org.jarvis.desktop.service.DesktopServiceHealthChecker
import org.jarvis.launcher.HealthCheckService
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticsView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = DiagnosticsReadModel(apiClient)
    private val refreshExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-diagnostics").apply { isDaemon = true }
    }
    private val refreshInFlight = AtomicBoolean(false)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private val updatedLabel = Label("Waiting for diagnostics snapshot")
    private val refreshButton = Button("Refresh")

    private val runtimeSummaryPill = statusPill("Checking runtime")
    private val runtimeSummaryHeadline = summaryHeadlineLabel()
    private val runtimeSummaryDetail = summaryDetailLabel()

    private val endpointSummaryPill = statusPill("Checking endpoints")
    private val endpointSummaryHeadline = summaryHeadlineLabel()
    private val endpointSummaryDetail = summaryDetailLabel()

    private val logSummaryPill = statusPill("Checking logs")
    private val logSummaryHeadline = summaryHeadlineLabel()
    private val logSummaryDetail = summaryDetailLabel()

    private val runtimeMetaLabel = secondaryLabel()
    private val runtimeFactsGrid = FlowPane(12.0, 12.0).apply {
        styleClass += "diagnostics-facts-grid"
    }
    private val runtimeCoreContainer = VBox(10.0).apply {
        styleClass += "diagnostics-check-list"
    }
    private val runtimeOptionalContainer = VBox(10.0).apply {
        styleClass += "diagnostics-check-list"
    }
    private val runtimeReasonsContainer = VBox(8.0).apply {
        styleClass += "diagnostics-reasons-list"
    }

    private val endpointMetaLabel = secondaryLabel()
    private val endpointSourceValue = infoValueLabel()
    private val endpointApiValue = codeValueLabel()
    private val endpointVoiceValue = codeValueLabel()
    private val endpointPcValue = codeValueLabel()
    private val endpointChecksContainer = VBox(10.0).apply {
        styleClass += "diagnostics-check-list"
    }

    private val previewMetaLabel = secondaryLabel()
    private val previewContainer = VBox(12.0).apply {
        styleClass += "diagnostics-log-list"
    }

    private var refreshTask: ScheduledFuture<*>? = null

    init {
        styleClass += "shell-route-scroll"
        styleClass += "diagnostics-scroll"
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
            children += VBox(4.0).apply {
                children += Label("Diagnostics").apply { styleClass += "shell-page-title" }
                children += Label(
                    "Launcher/runtime health and desktop endpoint usability stay separate here. " +
                        "This screen is observation-first by design."
                ).apply {
                    styleClass += "diagnostics-note"
                    isWrapText = true
                }
            }

            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += updatedLabel.apply { styleClass += "diagnostics-updated-label" }
            children += refreshButton.apply { styleClass += "shell-action-button" }
        }

        val summaryGrid = FlowPane(16.0, 16.0).apply {
            styleClass += "diagnostics-summary-grid"
            children.addAll(
                summaryCard(
                    title = "Launcher / runtime layer",
                    pill = runtimeSummaryPill,
                    headline = runtimeSummaryHeadline,
                    detail = runtimeSummaryDetail
                ),
                summaryCard(
                    title = "Desktop / client layer",
                    pill = endpointSummaryPill,
                    headline = endpointSummaryHeadline,
                    detail = endpointSummaryDetail
                ),
                summaryCard(
                    title = "Log preview",
                    pill = logSummaryPill,
                    headline = logSummaryHeadline,
                    detail = logSummaryDetail
                )
            )
        }

        val runtimeSection = sectionCard(
            title = "Launcher / Runtime Health",
            subtitle = "Read-only launcher-side health model. Destructive runtime actions remain in the launcher compatibility UI.",
            contentNodes = listOf(
                runtimeMetaLabel,
                runtimeFactsGrid,
                nestedCard(
                    title = "Core services",
                    subtitle = "Services that define launcher/runtime readiness.",
                    body = runtimeCoreContainer
                ),
                nestedCard(
                    title = "Optional services",
                    subtitle = "Capabilities that can be degraded without making runtime unavailable.",
                    body = runtimeOptionalContainer
                ),
                nestedCard(
                    title = "Runtime reasons",
                    subtitle = "Launcher-side reasoning for the current runtime state.",
                    body = runtimeReasonsContainer
                )
            )
        )

        val endpointSection = sectionCard(
            title = "Desktop / Client Endpoint Probes",
            subtitle = "Checks the exact endpoints resolved by the desktop client, including API and WebSocket usability.",
            contentNodes = listOf(
                endpointMetaLabel,
                nestedCard(
                    title = "Resolved desktop targets",
                    subtitle = "The URLs the unified shell is currently trying to use.",
                    body = endpointInfoGrid()
                ),
                nestedCard(
                    title = "Endpoint probe results",
                    subtitle = "Per-service usability checks from the client-facing layer.",
                    body = endpointChecksContainer
                )
            )
        )

        val logSection = sectionCard(
            title = "Recent Diagnostics Preview",
            subtitle = "Masked preview of launcher-side logs. This remains read-only and intentionally lighter than the legacy LogViewer.",
            contentNodes = listOf(previewMetaLabel, previewContainer)
        )

        return VBox(18.0).apply {
            styleClass += "shell-diagnostics-view"
            padding = Insets(24.0)
            children.addAll(header, summaryGrid, runtimeSection, endpointSection, logSection)
        }
    }

    private fun endpointInfoGrid(): GridPane {
        return GridPane().apply {
            styleClass += "diagnostics-info-grid"
            hgap = 16.0
            vgap = 12.0
            add(infoLabel("Source"), 0, 0)
            add(endpointSourceValue, 1, 0)
            add(infoLabel("API client"), 0, 1)
            add(endpointApiValue, 1, 1)
            add(infoLabel("Voice WebSocket"), 0, 2)
            add(endpointVoiceValue, 1, 2)
            add(infoLabel("PC Control WebSocket"), 0, 3)
            add(endpointPcValue, 1, 3)
        }
    }

    private fun summaryCard(
        title: String,
        pill: Label,
        headline: Label,
        detail: Label
    ): VBox {
        return VBox(10.0).apply {
            styleClass += "shell-section-card"
            styleClass += "diagnostics-summary-card"
            prefWidth = 300.0
            minWidth = 260.0
            maxWidth = Double.MAX_VALUE
            children += Label(title).apply { styleClass += "diagnostics-summary-title" }
            children += pill
            children += headline
            children += detail
        }
    }

    private fun sectionCard(
        title: String,
        subtitle: String,
        contentNodes: List<Node>
    ): VBox {
        return VBox(14.0).apply {
            styleClass += "shell-section-card"
            children += Label(title).apply { styleClass += "shell-section-title" }
            children += Label(subtitle).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children.addAll(contentNodes)
        }
    }

    private fun nestedCard(title: String, subtitle: String, body: Node): VBox {
        return VBox(10.0).apply {
            styleClass += "diagnostics-nested-card"
            children += Label(title).apply { styleClass += "diagnostics-block-title" }
            children += Label(subtitle).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += body
        }
    }

    private fun refreshNow() {
        if (!refreshInFlight.compareAndSet(false, true)) {
            return
        }

        Platform.runLater {
            refreshButton.isDisable = true
            updatedLabel.text = "Refreshing diagnostics..."
        }

        refreshExecutor.execute {
            try {
                val snapshot = readModel.refresh()
                Platform.runLater { render(snapshot) }
            } catch (e: Exception) {
                Platform.runLater { renderFailure(e) }
            } finally {
                refreshInFlight.set(false)
                Platform.runLater {
                    refreshButton.isDisable = false
                }
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

    private fun render(snapshot: DiagnosticsReadModel.Snapshot) {
        updatedLabel.text = "Updated ${timeFormatter.format(snapshot.refreshedAt)}"

        renderRuntime(snapshot.runtime)
        renderEndpoints(snapshot.endpoints)
        renderLogPreviews(snapshot.logPreviews)
    }

    private fun renderFailure(error: Exception) {
        updatedLabel.text = "Diagnostics refresh failed"
        previewMetaLabel.text = error.message ?: "Unknown diagnostics error"
        logSummaryPill.text = "Refresh failed"
        applyTone(logSummaryPill, "shell-status-tone-error")
    }

    private fun renderRuntime(runtime: DiagnosticsReadModel.RuntimeSnapshot) {
        val status = runtime.status
        val coreUp = status.coreServices.values.count {
            it.status == HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP
        }
        val optionalProblems = status.optionalServices.values.count {
            it.status == HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN ||
                (it.status == HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN && !it.isDisabled)
        }

        runtimeSummaryPill.text = "Runtime ${status.overall.name}"
        applyTone(runtimeSummaryPill, toneForRuntime(status.overall))
        runtimeSummaryHeadline.text = runtimeHeadline(status.overall)
        runtimeSummaryDetail.text = listOf(
            "Mode ${runtime.runtimeMode.uppercase()}",
            "Core $coreUp/${status.coreServices.size} up",
            "Optional issues $optionalProblems"
        ).joinToString("  |  ")

        runtimeMetaLabel.text = buildRuntimeMeta(runtime)
        renderRuntimeFacts(runtime)
        renderRuntimeChecks(
            container = runtimeCoreContainer,
            checks = status.coreServices.values.toList(),
            emptyState = "No core runtime checks were reported."
        )
        renderRuntimeChecks(
            container = runtimeOptionalContainer,
            checks = status.optionalServices.values.toList(),
            emptyState = "No optional runtime checks were reported."
        )
        renderReasons(status.reasons)
    }

    private fun renderRuntimeFacts(runtime: DiagnosticsReadModel.RuntimeSnapshot) {
        val pidText = runtime.backendPid?.let {
            "$it (${if (runtime.backendProcessAlive) "alive" else "not alive"})"
        } ?: "not found"

        runtimeFactsGrid.children.setAll(
            metricCard("Mode", runtime.runtimeMode.uppercase()),
            metricCard("Backend PID", pidText),
            metricCard("API target", runtime.apiBaseUrl),
            metricCard(
                "Feature flags",
                listOf(
                    "LLM ${if (runtime.llmEnabled) "enabled" else "disabled"}",
                    "Memory ${if (runtime.memoryEnabled) "enabled" else "disabled"}",
                    "Voice required ${if (runtime.voiceRequired) "yes" else "no"}"
                ).joinToString("  |  ")
            )
        )
    }

    private fun renderRuntimeChecks(
        container: VBox,
        checks: List<HealthCheckService.ServiceHealthStatus.ServiceCheck>,
        emptyState: String
    ) {
        container.children.clear()

        if (checks.isEmpty()) {
            container.children += emptyStateLabel(emptyState)
            return
        }

        checks.forEach { check ->
            container.children += VBox(8.0).apply {
                styleClass += "diagnostics-check-row"
                children += HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children += Label(check.name).apply { styleClass += "diagnostics-check-title" }
                    val spacer = Region()
                    HBox.setHgrow(spacer, Priority.ALWAYS)
                    children += spacer
                    children += statusPill(runtimeStatusLabel(check)).apply {
                        applyTone(this, toneForRuntimeCheck(check))
                    }
                }
                children += Label(check.message).apply {
                    styleClass += "diagnostics-check-detail"
                    isWrapText = true
                }
            }
        }
    }

    private fun renderReasons(reasons: List<String>) {
        runtimeReasonsContainer.children.clear()

        if (reasons.isEmpty()) {
            runtimeReasonsContainer.children += emptyStateLabel("No runtime reasons were reported.")
            return
        }

        reasons.forEach { reason ->
            runtimeReasonsContainer.children += Label(reason).apply {
                styleClass += "diagnostics-reason-line"
                isWrapText = true
            }
        }
    }

    private fun renderEndpoints(endpoints: DiagnosticsReadModel.EndpointSnapshot) {
        endpointSummaryPill.text = buildEndpointHeadline(endpoints.checks)
        applyTone(endpointSummaryPill, toneForEndpoint(endpoints.checks))
        endpointSummaryHeadline.text = endpointHeadline(endpoints.checks)
        endpointSummaryDetail.text = buildEndpointCounts(endpoints.checks)

        endpointMetaLabel.text = buildEndpointMeta(endpoints)
        endpointSourceValue.text = endpoints.config.apiGatewaySource.description
        endpointApiValue.text = endpoints.config.apiBaseUrl
        endpointVoiceValue.text = endpoints.config.voiceWebSocketUrl
        endpointPcValue.text = endpoints.config.pcControlWebSocketUrl
        renderEndpointChecks(endpoints.checks)
    }

    private fun renderEndpointChecks(checks: List<DesktopServiceHealthChecker.ServiceCheck>) {
        endpointChecksContainer.children.clear()

        if (checks.isEmpty()) {
            endpointChecksContainer.children += emptyStateLabel("No endpoint probe results were returned.")
            return
        }

        checks.forEach { check ->
            endpointChecksContainer.children += VBox(8.0).apply {
                styleClass += "diagnostics-check-row"
                children += HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children += Label(check.name).apply { styleClass += "diagnostics-check-title" }
                    val spacer = Region()
                    HBox.setHgrow(spacer, Priority.ALWAYS)
                    children += spacer
                    children += statusPill(check.status.name).apply {
                        applyTone(this, toneForEndpointCheck(check.status))
                    }
                }
                children += Label(check.target).apply {
                    styleClass += "diagnostics-check-target"
                    isWrapText = true
                }
                children += Label(check.detail.ifBlank { "No extra detail." }).apply {
                    styleClass += "diagnostics-check-detail"
                    isWrapText = true
                }
            }
        }
    }

    private fun renderLogPreviews(previews: List<DiagnosticsReadModel.LogPreview>) {
        val existing = previews.count { it.exists }
        val missing = previews.size - existing

        logSummaryPill.text = when {
            previews.isEmpty() -> "No log sources"
            missing == 0 -> "Logs available"
            existing > 0 -> "Logs partial"
            else -> "Logs missing"
        }
        applyTone(logSummaryPill, toneForLogs(previews))
        logSummaryHeadline.text = when {
            previews.isEmpty() -> "No diagnostics log sources configured"
            missing == 0 -> "All preview log sources are available"
            existing > 0 -> "$existing preview source(s) available, $missing missing"
            else -> "No preview log sources are currently available"
        }
        logSummaryDetail.text = previews.joinToString("  |  ") { it.label }

        previewMetaLabel.text = listOf(
            "Sources ${previews.size}",
            "Available $existing",
            "Missing $missing"
        ).joinToString("  |  ")

        previewContainer.children.clear()
        if (previews.isEmpty()) {
            previewContainer.children += emptyStateLabel("No launcher log previews were returned.")
            return
        }

        previews.forEach { preview ->
            previewContainer.children += VBox(10.0).apply {
                styleClass += "diagnostics-log-card"
                children += HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children += Label(preview.label).apply { styleClass += "diagnostics-block-title" }
                    val spacer = Region()
                    HBox.setHgrow(spacer, Priority.ALWAYS)
                    children += spacer
                    children += statusPill(if (preview.exists) "Available" else "Missing").apply {
                        applyTone(
                            this,
                            if (preview.exists) "shell-status-tone-success" else "shell-status-tone-warning"
                        )
                    }
                }
                children += Label(preview.path.toAbsolutePath().toString()).apply {
                    styleClass += "diagnostics-log-path"
                    isWrapText = true
                }
                children += readOnlyArea(if (preview.exists) 10 else 5).apply {
                    text = preview.text
                }
            }
        }
    }

    private fun metricCard(title: String, value: String): VBox {
        return VBox(6.0).apply {
            styleClass += "diagnostics-metric-card"
            prefWidth = 220.0
            minWidth = 180.0
            children += Label(title).apply { styleClass += "diagnostics-metric-title" }
            children += Label(value).apply {
                styleClass += "diagnostics-metric-value"
                isWrapText = true
            }
        }
    }

    private fun infoLabel(text: String): Label {
        return Label(text).apply { styleClass += "diagnostics-info-label" }
    }

    private fun infoValueLabel(): Label {
        return Label().apply {
            styleClass += "diagnostics-info-value"
            isWrapText = true
        }
    }

    private fun codeValueLabel(): Label {
        return Label().apply {
            styleClass.addAll("diagnostics-info-value", "diagnostics-code-value")
            isWrapText = true
        }
    }

    private fun emptyStateLabel(text: String): Label {
        return Label(text).apply {
            styleClass += "diagnostics-empty-state"
            isWrapText = true
        }
    }

    private fun buildRuntimeMeta(runtime: DiagnosticsReadModel.RuntimeSnapshot): String {
        val pidText = runtime.backendPid?.let {
            "$it (${if (runtime.backendProcessAlive) "alive" else "not alive"})"
        } ?: "not found"

        return listOf(
            "Mode ${runtime.runtimeMode.uppercase()}",
            "API ${runtime.apiBaseUrl}",
            "PID $pidText",
            "LLM ${if (runtime.llmEnabled) "enabled" else "disabled"}",
            "Memory ${if (runtime.memoryEnabled) "enabled" else "disabled"}",
            "Voice required ${if (runtime.voiceRequired) "yes" else "no"}"
        ).joinToString("  |  ")
    }

    private fun buildEndpointMeta(endpoints: DiagnosticsReadModel.EndpointSnapshot): String {
        val config = endpoints.config
        return listOf(
            "Source ${config.apiGatewaySource.description}",
            "Decision ${config.apiGatewayReason}",
            buildEndpointCounts(endpoints.checks)
        ).joinToString("  |  ")
    }

    private fun buildEndpointCounts(checks: List<DesktopServiceHealthChecker.ServiceCheck>): String {
        val online = checks.count { it.status == DesktopServiceHealthChecker.Status.ONLINE }
        val unauthorized = checks.count { it.status == DesktopServiceHealthChecker.Status.UNAUTHORIZED }
        val offline = checks.count { it.status == DesktopServiceHealthChecker.Status.OFFLINE }

        return "Online $online  |  Unauthorized $unauthorized  |  Offline $offline"
    }

    /**
     * Single derivation point for the endpoint summary pill/headline/tone.
     *
     * A 401/403 ([DesktopServiceHealthChecker.Status.UNAUTHORIZED]) means the
     * endpoint is reachable — it must not be treated as a warning-worthy
     * condition, matching how Service Status already classifies the same HTTP
     * signal as [org.jarvis.desktop.features.status.StatusLevel.PROTECTED] (healthy).
     */
    private enum class EndpointSummary { EMPTY, OFFLINE, PROTECTED_ONLY, ALL_ONLINE }

    private fun endpointSummary(checks: List<DesktopServiceHealthChecker.ServiceCheck>): EndpointSummary {
        return when {
            checks.isEmpty() -> EndpointSummary.EMPTY
            checks.any { it.status == DesktopServiceHealthChecker.Status.OFFLINE } -> EndpointSummary.OFFLINE
            checks.all { it.status == DesktopServiceHealthChecker.Status.ONLINE } -> EndpointSummary.ALL_ONLINE
            else -> EndpointSummary.PROTECTED_ONLY
        }
    }

    private fun buildEndpointHeadline(checks: List<DesktopServiceHealthChecker.ServiceCheck>): String {
        return when (endpointSummary(checks)) {
            EndpointSummary.EMPTY -> "Endpoints checking"
            EndpointSummary.OFFLINE -> "Endpoints degraded"
            EndpointSummary.ALL_ONLINE -> "Endpoints reachable"
            EndpointSummary.PROTECTED_ONLY -> "Endpoints reachable (protected)"
        }
    }

    private fun runtimeHeadline(status: HealthCheckService.ServiceHealthStatus.OverallStatus): String {
        return when (status) {
            HealthCheckService.ServiceHealthStatus.OverallStatus.READY -> "Launcher/runtime layer is ready"
            HealthCheckService.ServiceHealthStatus.OverallStatus.DEGRADED -> "Launcher/runtime layer is degraded"
            HealthCheckService.ServiceHealthStatus.OverallStatus.STARTING -> "Launcher/runtime layer is still starting"
            HealthCheckService.ServiceHealthStatus.OverallStatus.ERROR -> "Launcher/runtime layer needs attention"
            HealthCheckService.ServiceHealthStatus.OverallStatus.IDLE -> "Launcher/runtime layer is idle"
        }
    }

    private fun endpointHeadline(checks: List<DesktopServiceHealthChecker.ServiceCheck>): String {
        return when (endpointSummary(checks)) {
            EndpointSummary.EMPTY -> "Desktop/client endpoint probes are still settling"
            EndpointSummary.OFFLINE -> "Desktop/client layer has connectivity issues"
            EndpointSummary.ALL_ONLINE -> "Desktop/client endpoints are reachable"
            EndpointSummary.PROTECTED_ONLY -> "Desktop/client endpoints are reachable — some require authentication"
        }
    }

    private fun statusPill(text: String): Label {
        return Label(text).apply {
            styleClass.addAll("shell-status-pill", "diagnostics-status-pill", "shell-status-tone-muted")
        }
    }

    private fun summaryHeadlineLabel(): Label {
        return Label().apply {
            styleClass += "diagnostics-summary-headline"
            isWrapText = true
        }
    }

    private fun summaryDetailLabel(): Label {
        return Label().apply {
            styleClass += "diagnostics-summary-detail"
            isWrapText = true
        }
    }

    private fun secondaryLabel(): Label {
        return Label().apply {
            styleClass += "diagnostics-meta"
            isWrapText = true
        }
    }

    private fun readOnlyArea(prefRowCount: Int): TextArea {
        return TextArea().apply {
            isEditable = false
            isWrapText = true
            this.prefRowCount = prefRowCount
            styleClass += "diagnostics-readonly-area"
        }
    }

    private fun applyTone(label: Label, toneClass: String) {
        label.styleClass.removeIf {
            it == "shell-status-pill" ||
                it == "diagnostics-status-pill" ||
                it.startsWith("shell-status-tone-")
        }
        label.styleClass.addAll("shell-status-pill", "diagnostics-status-pill", toneClass)
    }

    private fun toneForRuntime(status: HealthCheckService.ServiceHealthStatus.OverallStatus): String =
        status.toStatusLevel().toneStyleClass

    private fun toneForRuntimeCheck(check: HealthCheckService.ServiceHealthStatus.ServiceCheck): String =
        check.toStatusLevel().toneStyleClass

    private fun toneForEndpoint(checks: List<DesktopServiceHealthChecker.ServiceCheck>): String {
        return when (endpointSummary(checks)) {
            EndpointSummary.EMPTY -> StatusLevel.UNKNOWN.toneStyleClass
            EndpointSummary.OFFLINE -> StatusLevel.DOWN.toneStyleClass
            EndpointSummary.ALL_ONLINE -> StatusLevel.UP.toneStyleClass
            EndpointSummary.PROTECTED_ONLY -> StatusLevel.PROTECTED.toneStyleClass
        }
    }

    private fun toneForEndpointCheck(status: DesktopServiceHealthChecker.Status): String =
        status.toStatusLevel().toneStyleClass

    private fun toneForLogs(previews: List<DiagnosticsReadModel.LogPreview>): String {
        val existing = previews.count { it.exists }
        return when {
            previews.isEmpty() -> "shell-status-tone-muted"
            existing == previews.size -> "shell-status-tone-success"
            existing > 0 -> "shell-status-tone-warning"
            else -> "shell-status-tone-error"
        }
    }

    private fun runtimeStatusLabel(check: HealthCheckService.ServiceHealthStatus.ServiceCheck): String {
        return if (check.isDisabled) "DISABLED" else check.status.name
    }
}
