package org.jarvis.desktop.features.analytics

import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.geometry.Side
import javafx.scene.Node
import javafx.scene.chart.BarChart
import javafx.scene.chart.CategoryAxis
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.PieChart
import javafx.scene.chart.XYChart
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.BorderPane
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
 * Analytics dashboard — real JavaFX charts (bar/pie/line) driven by
 * [AnalyticsReadModel], dark-themed with a view-local stylesheet
 * ([analytics-charts.css][/css/analytics-charts.css]) to match the shell
 * chrome. Each chart card degrades independently to an honest
 * "unavailable" placeholder when its endpoint is down, mirroring
 * [org.jarvis.desktop.features.insights.InsightsView]'s per-section
 * resilience.
 */
class AnalyticsView(
    apiClient: ApiClient
) : BorderPane(), ShellRouteContent {
    private val readModel = AnalyticsReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-analytics").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Analytics")
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { refresh() }
    }

    private val monthXAxis = CategoryAxis()
    private val monthYAxis = NumberAxis()
    private val monthChart = BarChart(monthXAxis, monthYAxis).apply {
        styleClass += "analytics-chart"
        isLegendVisible = false
        animated = false
        prefHeight = 300.0
        monthYAxis.label = "Amount"
    }

    private val categoryChart = PieChart().apply {
        styleClass += "analytics-chart"
        isLegendVisible = true
        legendSide = Side.BOTTOM
        animated = false
        prefHeight = 300.0
    }

    private val trendXAxis = CategoryAxis()
    private val trendYAxis = NumberAxis()
    private val trendChart = LineChart(trendXAxis, trendYAxis).apply {
        styleClass += "analytics-chart"
        isLegendVisible = false
        animated = false
        createSymbols = true
        prefHeight = 300.0
    }

    private val monthPill = ShellPanelSupport.statusPill("Monthly")
    private val categoryPill = ShellPanelSupport.statusPill("Category")
    private val trendPill = ShellPanelSupport.statusPill("Trend")

    private val monthCardBody = VBox(8.0).apply { children += placeholder("Loading…") }
    private val categoryCardBody = VBox(8.0).apply { children += placeholder("Loading…") }
    private val trendCardBody = VBox(8.0).apply { children += placeholder("Loading…") }

    init {
        styleClass += "shell-analytics-view"
        stylesheets += requireNotNull(javaClass.getResource("/css/analytics-charts.css")) {
            "analytics-charts.css missing from classpath"
        }.toExternalForm()
        center = host(buildContent())
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
                children += Label("Analytics Dashboard").apply { styleClass += "shell-page-title" }
                children += Label("Expenses over time, by category, and their trend — from live analytics data.").apply {
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

        val topRow = HBox(18.0).apply {
            children += chartCard(
                "Monthly Expenses",
                monthPill,
                "/api/v1/analytics/expenses/by-month",
                monthCardBody
            ).also { HBox.setHgrow(it, Priority.ALWAYS) }
            children += chartCard(
                "Expenses by Category",
                categoryPill,
                "/api/v1/analytics/expenses/by-category",
                categoryCardBody
            ).also { HBox.setHgrow(it, Priority.ALWAYS) }
        }

        val trendCard = chartCard(
            "Expense Trend",
            trendPill,
            "/api/v1/analytics/expenses/trend",
            trendCardBody
        )

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, topRow, trendCard)
        }
    }

    private fun chartCard(title: String, pill: Label, endpoint: String, body: Node): Node {
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
            children += body
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
                    bindMonth(snapshot.byMonth)
                    bindCategory(snapshot.byCategory)
                    bindTrend(snapshot.trend)
                    val anyUp = listOf(snapshot.byMonth, snapshot.byCategory, snapshot.trend)
                        .any { it is AnalyticsReadModel.Result.Available }
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

    private fun bindMonth(result: AnalyticsReadModel.Result<List<AnalyticsReadModel.ExpensePoint>>) {
        when (result) {
            is AnalyticsReadModel.Result.Available -> {
                monthPill.text = "OK"
                ShellPanelSupport.applyTone(monthPill, "shell-status-tone-success")
                if (result.data.isEmpty()) {
                    showPlaceholder(monthCardBody, monthChart, "No expense data available.")
                } else {
                    val series = XYChart.Series<String, Number>()
                    series.name = "Monthly expenses"
                    result.data.forEach { point -> series.data.add(XYChart.Data(point.label, point.amount)) }
                    monthChart.data.setAll(series)
                    showChart(monthCardBody, monthChart)
                }
            }
            is AnalyticsReadModel.Result.Unavailable -> {
                monthPill.text = "Unavailable"
                ShellPanelSupport.applyTone(monthPill, "shell-status-tone-warning")
                showPlaceholder(monthCardBody, monthChart, "Временно недоступно: ${result.reason}")
            }
        }
    }

    private fun bindCategory(result: AnalyticsReadModel.Result<List<AnalyticsReadModel.ExpensePoint>>) {
        when (result) {
            is AnalyticsReadModel.Result.Available -> {
                categoryPill.text = "OK"
                ShellPanelSupport.applyTone(categoryPill, "shell-status-tone-success")
                if (result.data.isEmpty()) {
                    showPlaceholder(categoryCardBody, categoryChart, "No expense data available.")
                } else {
                    categoryChart.data = FXCollections.observableArrayList(
                        result.data.map { point -> PieChart.Data("${point.label} (${formatAmount(point)})", point.amount) }
                    )
                    showChart(categoryCardBody, categoryChart)
                }
            }
            is AnalyticsReadModel.Result.Unavailable -> {
                categoryPill.text = "Unavailable"
                ShellPanelSupport.applyTone(categoryPill, "shell-status-tone-warning")
                showPlaceholder(categoryCardBody, categoryChart, "Временно недоступно: ${result.reason}")
            }
        }
    }

    private fun bindTrend(result: AnalyticsReadModel.Result<AnalyticsReadModel.Series>) {
        when (result) {
            is AnalyticsReadModel.Result.Available -> {
                trendPill.text = "OK"
                ShellPanelSupport.applyTone(trendPill, "shell-status-tone-success")
                if (result.data.points.isEmpty()) {
                    showPlaceholder(trendCardBody, trendChart, "No trend data available.")
                } else {
                    val series = XYChart.Series<String, Number>()
                    series.name = result.data.title
                    result.data.points.forEach { (label, value) -> series.data.add(XYChart.Data(label, value)) }
                    trendChart.data.setAll(series)
                    if (result.data.xAxisLabel.isNotBlank()) trendXAxis.label = result.data.xAxisLabel
                    if (result.data.yAxisLabel.isNotBlank()) trendYAxis.label = result.data.yAxisLabel
                    showChart(trendCardBody, trendChart)
                }
            }
            is AnalyticsReadModel.Result.Unavailable -> {
                trendPill.text = "Unavailable"
                ShellPanelSupport.applyTone(trendPill, "shell-status-tone-warning")
                showPlaceholder(trendCardBody, trendChart, "Временно недоступно: ${result.reason}")
            }
        }
    }

    private fun showChart(container: VBox, chart: Node) {
        container.children.setAll(chart)
    }

    private fun showPlaceholder(container: VBox, chart: Node, message: String) {
        when (chart) {
            is XYChart<*, *> -> chart.data.clear()
            is PieChart -> chart.data.clear()
        }
        container.children.setAll(placeholder(message))
    }

    private fun formatAmount(point: AnalyticsReadModel.ExpensePoint): String =
        "%.2f %s".format(point.amount, point.currency).trim()

    private fun placeholder(message: String): Node = Label(message).apply {
        styleClass += "analytics-chart-empty"
        isWrapText = true
    }

    private fun host(node: Node): Node {
        return if (node is ScrollPane) {
            node.apply {
                isFitToWidth = true
                styleClass += "shell-route-scroll"
            }
        } else {
            ScrollPane(node).apply {
                isFitToWidth = true
                hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
                styleClass += "shell-route-scroll"
            }
        }
    }
}
