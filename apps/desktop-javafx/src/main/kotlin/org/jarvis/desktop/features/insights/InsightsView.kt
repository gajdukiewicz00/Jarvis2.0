package org.jarvis.desktop.features.insights

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
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
 * payload, or an honest "временно недоступно" line when that endpoint is down.
 */
class InsightsView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = InsightsReadModel(apiClient)
    private val objectMapper = jacksonObjectMapper().writerWithDefaultPrettyPrinter()
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
    private val insightsArea = readOnlyArea()
    private val dayScorePill = ShellPanelSupport.statusPill("Day score")
    private val dayScoreArea = readOnlyArea()
    private val forecastPill = ShellPanelSupport.statusPill("Forecast")
    private val forecastArea = readOnlyArea()
    private val reportPill = ShellPanelSupport.statusPill("Report")
    private val reportArea = readOnlyArea()

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
                sectionCard("Day score", dayScorePill, dayScoreArea, "/api/v1/analytics/insights/day-score"),
                sectionCard("Forecast", forecastPill, forecastArea, "/api/v1/analytics/insights/forecast"),
                sectionCard("Insights", insightsPill, insightsArea, "/api/v1/analytics/insights"),
                sectionCard("Report", reportPill, reportArea, "/api/v1/analytics/insights/report")
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
                    bind(insightsPill, insightsArea, snapshot.insights)
                    bind(dayScorePill, dayScoreArea, snapshot.dayScore)
                    bind(forecastPill, forecastArea, snapshot.forecast)
                    bind(reportPill, reportArea, snapshot.report)
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

    private fun bind(pill: Label, area: TextArea, result: InsightsReadModel.Result) {
        when (result) {
            is InsightsReadModel.Result.Available -> {
                pill.text = "OK"
                ShellPanelSupport.applyTone(pill, "shell-status-tone-success")
                area.text = prettify(result.body)
            }
            is InsightsReadModel.Result.Unavailable -> {
                pill.text = "Unavailable"
                ShellPanelSupport.applyTone(pill, "shell-status-tone-warning")
                area.text = "Временно недоступно: ${result.reason}"
            }
        }
    }

    private fun prettify(body: String): String =
        runCatching {
            val mapper = jacksonObjectMapper()
            objectMapper.writeValueAsString(mapper.readTree(body))
        }.getOrDefault(body)

    private fun sectionCard(title: String, pill: Label, area: TextArea, endpoint: String): Node {
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
            children += area
        }
    }

    private fun readOnlyArea(): TextArea =
        TextArea().apply {
            isEditable = false
            isWrapText = true
            prefRowCount = 6
            styleClass += "diagnostics-readonly-area"
            text = "Loading…"
        }
}
