package org.jarvis.desktop.features.insights

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
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
 * intelligence from the analytics service.
 *
 * Day score, forecast, and insights each render as readable dark cards with
 * a small gauge/bar where the data is numeric (score-out-of-100, month
 * progress vs. budget pace) — see [renderDayScore], [renderForecast], and
 * [renderInsightsList]. Report keeps the original generic key/value-row
 * renderer ([renderResult]), which every section also falls back to if its
 * payload doesn't match the expected shape, or an honest
 * "временно недоступно" line when that endpoint is down — never a raw JSON
 * blob.
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
        stylesheets += requireNotNull(javaClass.getResource("/css/insights-gauges.css")) {
            "insights-gauges.css missing from classpath"
        }.toExternalForm()
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
                    bindSection(insightsPill, insightsBox, snapshot.insights, ::renderInsightsList)
                    bindSection(dayScorePill, dayScoreBox, snapshot.dayScore, ::renderDayScore)
                    bindSection(forecastPill, forecastBox, snapshot.forecast, ::renderForecast)
                    bindSection(reportPill, reportBox, snapshot.report, ::renderResult)
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

    /** Shared Available/Unavailable handling; [renderer] fills [box] from the raw body on success. */
    private fun bindSection(pill: Label, box: VBox, result: InsightsReadModel.Result, renderer: (VBox, String) -> Unit) {
        when (result) {
            is InsightsReadModel.Result.Available -> {
                pill.text = "OK"
                ShellPanelSupport.applyTone(pill, "shell-status-tone-success")
                renderer(box, result.body)
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

    /** Day score as a big number + grade pill + a 0..100 gauge, with the raw component breakdown below. */
    private fun renderDayScore(box: VBox, text: String) {
        val root = runCatching { objectMapper.readTree(text) }.getOrNull()
        val score = root?.path("score")?.takeIf { it.isNumber }?.asInt()
        val grade = root?.path("grade")?.textOrNull()
        if (root == null || score == null || grade == null) {
            renderResult(box, text)
            return
        }

        val toneSuffix = toneSuffixForGrade(grade)
        val card = VBox(10.0).apply {
            styleClass += "insights-score-card"
            children += HBox(14.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label("$score/100").apply { styleClass += "insights-score-value" }
                val pill = ShellPanelSupport.statusPill("Grade $grade")
                ShellPanelSupport.applyTone(pill, toneClassFor(toneSuffix))
                children += pill
            }
            children += gaugeBar(score / 100.0, toneSuffix)
            val components = root.path("components")
            if (components.isObject && components.size() > 0) {
                children += VBox(4.0).apply {
                    styleClass += "insights-json-nested"
                    renderNode(this, components)
                }
            }
        }
        box.children.setAll(card)
    }

    /** Budget forecast as a month-progress gauge plus a spend-pace gauge, with the raw numbers below. */
    private fun renderForecast(box: VBox, text: String) {
        val root = runCatching { objectMapper.readTree(text) }.getOrNull()
        val dayOfMonth = root?.path("dayOfMonth")?.takeIf { it.isNumber }?.asInt()
        val daysInMonth = root?.path("daysInMonth")?.takeIf { it.isNumber }?.asInt()
        val spentSoFar = root?.path("spentSoFar")?.takeIf { it.isNumber }?.asDouble()
        val projected = root?.path("projectedMonthEnd")?.takeIf { it.isNumber }?.asDouble()
        if (root == null || dayOfMonth == null || daysInMonth == null || daysInMonth <= 0 ||
            spentSoFar == null || projected == null
        ) {
            renderResult(box, text)
            return
        }

        val monthProgress = (dayOfMonth.toDouble() / daysInMonth).coerceIn(0.0, 1.0)
        val paceFraction = if (projected > 0.0) (spentSoFar / projected).coerceIn(0.0, 1.0) else 0.0
        val paceTone = when {
            projected <= 0.0 -> "info"
            paceFraction <= monthProgress + PACE_SAFE_MARGIN -> "success"
            paceFraction <= monthProgress + PACE_WARN_MARGIN -> "warning"
            else -> "error"
        }

        val card = VBox(10.0).apply {
            styleClass += "insights-score-card"
            root.path("month").textOrNull()?.let {
                children += Label("Month: $it").apply { styleClass += "insights-score-caption" }
            }
            children += Label("Day $dayOfMonth of $daysInMonth").apply { styleClass += "insights-json-value" }
            children += gaugeBar(monthProgress, "info")
            children += Label(
                "Spent so far: ${formatNumber(spentSoFar)}  ·  Projected month-end: ${formatNumber(projected)}"
            ).apply { styleClass += "insights-json-value" }
            children += gaugeBar(paceFraction, paceTone)
            root.path("dailyRate").takeIf { it.isNumber }?.let {
                children += Label("Daily rate: ${formatNumber(it.asDouble())}").apply {
                    styleClass += "insights-json-value"
                }
            }
        }
        box.children.setAll(card)
    }

    /** Insights as a list of readable cards (title, severity pill, detail) instead of raw key/value rows. */
    private fun renderInsightsList(box: VBox, text: String) {
        val root = runCatching { objectMapper.readTree(text) }.getOrNull()
        if (root == null || !root.isArray) {
            renderResult(box, text)
            return
        }
        if (root.size() == 0) {
            box.children.setAll(valueLabel("No insights right now."))
            return
        }
        val cards = root.mapNotNull { item ->
            val title = item.path("title").textOrNull() ?: return@mapNotNull null
            val detail = item.path("detail").textOrNull() ?: ""
            val severity = item.path("severity").textOrNull() ?: "INFO"
            insightCard(title, detail, severity)
        }
        if (cards.isEmpty()) {
            renderResult(box, text)
            return
        }
        box.children.setAll(cards)
    }

    private fun insightCard(title: String, detail: String, severity: String): Node = VBox(4.0).apply {
        styleClass += "insights-item-card"
        children += HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            children += Label(title).apply { styleClass += "insights-item-title" }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            val pill = ShellPanelSupport.statusPill(severity)
            ShellPanelSupport.applyTone(pill, toneClassFor(toneSuffixForSeverity(severity)))
            children += pill
        }
        if (detail.isNotBlank()) {
            children += Label(detail).apply {
                styleClass += "insights-item-detail"
                isWrapText = true
            }
        }
    }

    private fun gaugeBar(fraction: Double, toneSuffix: String): Node =
        ProgressBar(fraction.coerceIn(0.0, 1.0)).apply {
            styleClass += "insights-gauge"
            styleClass += "insights-gauge-$toneSuffix"
            maxWidth = Double.MAX_VALUE
        }

    private fun toneSuffixForGrade(grade: String): String = when (grade.uppercase()) {
        "A", "B" -> "success"
        "C" -> "warning"
        else -> "error"
    }

    private fun toneSuffixForSeverity(severity: String): String = when (severity.uppercase()) {
        "WARN", "WARNING" -> "warning"
        "ERROR" -> "error"
        else -> "info"
    }

    private fun toneClassFor(toneSuffix: String): String = when (toneSuffix) {
        "success" -> "shell-status-tone-success"
        "warning" -> "shell-status-tone-warning"
        "error" -> "shell-status-tone-error"
        else -> "shell-status-tone-info"
    }

    private fun formatNumber(value: Double): String = "%.2f".format(value)

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

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }

    private companion object {
        /** Spend-pace is "on track" if it's within this much of month-elapsed progress. */
        private const val PACE_SAFE_MARGIN = 0.05
        private const val PACE_WARN_MARGIN = 0.20
    }
}
