package org.jarvis.desktop.features.insights

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
 * Analytics Insights panel — day-score, forecast, insights, and report
 * intelligence from the analytics service. Each section renders its endpoint's
 * payload as labeled dark cards (key/value rows, recursing into nested
 * objects/arrays), or an honest "временно недоступно" line when that
 * endpoint is down — never a raw JSON blob.
 */
class InsightsView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = InsightsReadModel(apiClient)
    private val objectMapper = jacksonObjectMapper()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-insights").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Insights")
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { refresh() }
    }

    private val insightsPill = ShellPanelSupport.statusPill("Insights")
    private val insightsBox = resultBox()
    private val dayScorePill = ShellPanelSupport.statusPill("Day score")
    private val dayScoreBox = resultBox()
    private val forecastPill = ShellPanelSupport.statusPill("Forecast")
    private val forecastBox = resultBox()
    private val reportPill = ShellPanelSupport.statusPill("Report")
    private val reportBox = resultBox()

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-insights-view"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
    }

    override fun onRouteActivated() {
        refresh()
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(6.0).apply {
                children += Label("Analytics Insights").apply { styleClass += "shell-page-title" }
                children += Label("Day score, forecast, insights, and report — your week at a glance.").apply {
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

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(
                header,
                sectionCard("Day score", dayScorePill, dayScoreBox, "/api/v1/analytics/insights/day-score"),
                sectionCard("Forecast", forecastPill, forecastBox, "/api/v1/analytics/insights/forecast"),
                sectionCard("Insights", insightsPill, insightsBox, "/api/v1/analytics/insights"),
                sectionCard("Report", reportPill, reportBox, "/api/v1/analytics/insights/report")
            )
        }
    }

    private fun refresh() {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        refreshButton.isDisable = true
        statusPill.text = "Loading"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")

        worker.execute {
            try {
                val snapshot = readModel.refresh()
                Platform.runLater {
                    bind(insightsPill, insightsBox, snapshot.insights)
                    bind(dayScorePill, dayScoreBox, snapshot.dayScore)
                    bind(forecastPill, forecastBox, snapshot.forecast)
                    bind(reportPill, reportBox, snapshot.report)
                    val anyUp = listOf(snapshot.insights, snapshot.dayScore, snapshot.forecast, snapshot.report)
                        .any { it is InsightsReadModel.Result.Available }
                    statusPill.text = if (anyUp) "Ready" else "Unavailable"
                    ShellPanelSupport.applyTone(
                        statusPill,
                        if (anyUp) "shell-status-tone-success" else "shell-status-tone-warning"
                    )
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { refreshButton.isDisable = false }
            }
        }
    }

    private fun bind(pill: Label, box: VBox, result: InsightsReadModel.Result) {
        when (result) {
            is InsightsReadModel.Result.Available -> {
                pill.text = "OK"
                ShellPanelSupport.applyTone(pill, "shell-status-tone-success")
                renderResult(box, result.body)
            }
            is InsightsReadModel.Result.Unavailable -> {
                pill.text = "Unavailable"
                ShellPanelSupport.applyTone(pill, "shell-status-tone-warning")
                box.children.setAll(rawBlock("Временно недоступно: ${result.reason}"))
            }
        }
    }

    private fun sectionCard(title: String, pill: Label, box: VBox, endpoint: String): Node {
        return VBox(10.0).apply {
            styleClass += "shell-section-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += ShellPanelSupport.sectionTitle(title)
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                children += pill
            }
            children += Label(endpoint).apply { styleClass += "diagnostics-code-value" }
            children += box
        }
    }

    private fun resultBox(): VBox = VBox(4.0).apply {
        styleClass += "insights-result-box"
        children += valueLabel("Loading…")
    }

    /** Parses [text] as JSON and renders it as labeled rows; falls back to a raw block if it isn't JSON. */
    private fun renderResult(box: VBox, text: String) {
        box.children.clear()
        val node = runCatching { objectMapper.readTree(text) }.getOrNull()
        if (node == null || node.isMissingNode) {
            box.children += rawBlock(text)
            return
        }
        renderNode(box, node)
    }

    private fun renderNode(container: VBox, node: JsonNode) {
        when {
            node.isObject -> {
                if (node.size() == 0) {
                    container.children += valueLabel("No data.")
                    return
                }
                node.fields().forEachRemaining { entry ->
                    container.children += fieldRow(entry.key, entry.value)
                }
            }
            node.isArray -> {
                if (node.size() == 0) {
                    container.children += valueLabel("No data.")
                    return
                }
                node.forEachIndexed { index, value ->
                    container.children += fieldRow("[$index]", value)
                }
            }
            else -> container.children += valueLabel(scalarText(node))
        }
    }

    private fun fieldRow(key: String, value: JsonNode): Node {
        return if (value.isObject || value.isArray) {
            VBox(4.0).apply {
                styleClass += "insights-json-group"
                children += Label(labelize(key)).apply { styleClass += "insights-json-key" }
                children += VBox(4.0).apply {
                    styleClass += "insights-json-nested"
                    renderNode(this, value)
                }
            }
        } else {
            HBox(8.0).apply {
                styleClass += "insights-json-row"
                children += Label(labelize(key)).apply { styleClass += "insights-json-key" }
                children += Label(scalarText(value)).apply {
                    styleClass += "insights-json-value"
                    isWrapText = true
                }
            }
        }
    }

    private fun scalarText(node: JsonNode): String = when {
        node.isNull -> "—"
        node.isTextual -> node.asText()
        else -> node.toString()
    }

    /** "dayScore" -> "Day score", "total_events" -> "Total events". */
    private fun labelize(key: String): String {
        val spaced = key.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").replace('_', ' ')
        return spaced.replaceFirstChar { it.uppercase() }
    }

    private fun valueLabel(text: String): Label = Label(text).apply { styleClass += "insights-json-value" }

    private fun rawBlock(text: String): Node = Label(text).apply {
        styleClass += "insights-raw-block"
        isWrapText = true
    }
}
