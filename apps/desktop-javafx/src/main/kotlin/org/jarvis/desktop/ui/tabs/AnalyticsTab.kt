package org.jarvis.desktop.ui.tabs

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.AppConfig

class AnalyticsTab(private val apiClient: ApiClient) {
    val tab = Tab("Analytics")
    private val statusLabel = Label("")
    private val expensesByMonthArea = TextArea()
    private val expensesByCategoryArea = TextArea()
    private val timeStatsArea = TextArea()
    private val calendarStatsArea = TextArea()

    init {
        val content = VBox(10.0)
        content.padding = Insets(10.0)
        
        // Title
        val title = Label("Analytics Dashboard")
        title.style = "-fx-font-size: 18px; -fx-font-weight: bold;"
        content.children.add(title)
        
        // Status label
        statusLabel.style = "-fx-font-weight: bold;"
        content.children.add(statusLabel)

        // Refresh button
        val refreshBtn = Button("🔄 Refresh All")
        refreshBtn.setOnAction { loadAllAnalytics() }
        content.children.add(refreshBtn)

        // Grid for analytics sections
        val grid = GridPane()
        grid.hgap = 10.0
        grid.vgap = 10.0
        grid.padding = Insets(10.0)

        // Expenses by Month
        val monthSection = createSection(
            "📅 Monthly Expenses",
            expensesByMonthArea,
            "Month-by-month expense breakdown"
        )
        grid.add(monthSection, 0, 0)

        // Expenses by Category
        val categorySection = createSection(
            "📊 Expenses by Category",
            expensesByCategoryArea,
            "Total spending per category"
        )
        grid.add(categorySection, 1, 0)

        // Time Tracking Stats
        val timeSection = createSection(
            "⏱️ Time Statistics",
            timeStatsArea,
            "Time spent by category"
        )
        grid.add(timeSection, 0, 1)

        // Calendar Stats
        val calendarSection = createSection(
            "📆 Calendar Overview",
            calendarStatsArea,
            "Event statistics"
        )
        grid.add(calendarSection, 1, 1)

        content.children.add(grid)

        tab.content = content
        tab.isClosable = false

        // Load data on init
        loadAllAnalytics()
    }

    fun refresh() {
        loadAllAnalytics()
    }

    private fun createSection(title: String, textArea: TextArea, description: String): VBox {
        val section = VBox(5.0)
        section.style = "-fx-border-color: lightgray; -fx-border-width: 1; -fx-padding: 10;"
        section.prefWidth = 400.0

        val titleLabel = Label(title)
        titleLabel.style = "-fx-font-weight: bold; -fx-font-size: 14px;"

        val descLabel = Label(description)
        descLabel.style = "-fx-font-size: 10px; -fx-text-fill: gray;"

        textArea.isEditable = false
        textArea.prefHeight = 150.0
        textArea.style = "-fx-font-family: monospace; -fx-font-size: 11px;"

        section.children.addAll(titleLabel, descLabel, textArea)
        return section
    }

    private fun loadAllAnalytics() {
        statusLabel.text = "Loading analytics..."
        statusLabel.style = "-fx-text-fill: blue; -fx-font-weight: bold;"

        Thread {
            try {
                val failures = listOfNotNull(
                    loadExpensesByMonth(),
                    loadExpensesByCategory(),
                    loadTimeStatistics(),
                    loadCalendarStatistics()
                )

                Platform.runLater {
                    if (failures.isEmpty()) {
                        statusLabel.text = "✓ Analytics loaded successfully"
                        statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
                    } else {
                        statusLabel.text = "✗ Analytics failed in ${failures.size} pane(s): ${failures.joinToString(", ")}"
                        statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"
                    }
                }
            } catch (e: Exception) {
                val errorMessage = e.message ?: "Unknown error"
                Platform.runLater {
                    statusLabel.text = when {
                        e is org.jarvis.desktop.api.AccessDeniedException ->
                            "✗ Access denied. Check authorization configuration or user roles."
                        errorMessage.contains("Connection refused") || errorMessage.contains("not available") ->
                            "✗ Server unavailable. Please start the API gateway (${AppConfig.apiGatewayBaseUrl})"
                        errorMessage.contains("not found (404)") ->
                            "✗ Analytics service not deployed. Start it with the local runtime."
                        else ->
                            "✗ Error loading analytics: $errorMessage"
                    }
                    statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"
                }
            }
        }.apply {
            isDaemon = true
            name = "jarvis-desktop-analytics-load"
            start()
        }
    }

    private fun loadExpensesByMonth(): String? {
        try {
            val response = apiClient.get("/analytics/expenses/by-month")
            val formatted = formatExpenseData(response, "Month")
            Platform.runLater {
                expensesByMonthArea.text = formatted
            }
            return null
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Unknown error"
            Platform.runLater {
                expensesByMonthArea.text = if (errorMessage.contains("Connection refused") || errorMessage.contains("not available")) {
                    "Server unavailable.\nPlease start the API gateway\n(${AppConfig.apiGatewayBaseUrl})"
                } else {
                    "Error: $errorMessage"
                }
            }
            return "monthly expenses"
        }
    }

    private fun loadExpensesByCategory(): String? {
        try {
            val response = apiClient.get("/analytics/expenses/by-category")
            val formatted = formatExpenseData(response, "Category")
            Platform.runLater {
                expensesByCategoryArea.text = formatted
            }
            return null
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Unknown error"
            Platform.runLater {
                expensesByCategoryArea.text = if (errorMessage.contains("Connection refused") || errorMessage.contains("not available")) {
                    "Server unavailable.\nPlease start the API gateway\n(${AppConfig.apiGatewayBaseUrl})"
                } else {
                    "Error: $errorMessage"
                }
            }
            return "category expenses"
        }
    }

    private fun loadTimeStatistics(): String? {
        try {
            val response = apiClient.get("/analytics/time/summary")
            val formatted = formatTimeData(response)
            Platform.runLater {
                timeStatsArea.text = formatted
            }
            return null
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Unknown error"
            Platform.runLater {
                timeStatsArea.text = if (errorMessage.contains("Connection refused") || errorMessage.contains("not available")) {
                    "Server unavailable.\nPlease start the API gateway\n(${AppConfig.apiGatewayBaseUrl})"
                } else {
                    "Error: $errorMessage"
                }
            }
            return "time statistics"
        }
    }

    private fun loadCalendarStatistics(): String? {
        try {
            val response = apiClient.get("/analytics/calendar/summary")
            val formatted = formatCalendarData(response)
            Platform.runLater {
                calendarStatsArea.text = formatted
            }
            return null
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Unknown error"
            Platform.runLater {
                calendarStatsArea.text = if (errorMessage.contains("Connection refused") || errorMessage.contains("not available")) {
                    "Server unavailable.\nPlease start the API gateway\n(${AppConfig.apiGatewayBaseUrl})"
                } else {
                    "Error: $errorMessage"
                }
            }
            return "calendar overview"
        }
    }

    private fun formatExpenseData(json: String, groupBy: String): String {
        if (json.trim() == "[]") return "No expense data available"

        val builder = StringBuilder()
        builder.append(String.format("%-15s %10s %8s\n", groupBy, "Amount", "Count"))
        builder.append("-".repeat(35) + "\n")

        // Simple JSON parsing
        val items = json.trim().removePrefix("[").removeSuffix("]").split("},")
        for (item in items) {
            try {
                val period = extractJsonValue(item, if (groupBy == "Month") "period" else "category")
                val amount = extractJsonValue(item, "totalAmount").toDoubleOrNull() ?: 0.0
                val currency = extractJsonValue(item, "currency")
                val count = extractJsonValue(item, "count")

                builder.append(String.format("%-15s %7s%.2f %8s\n", period, currency, amount, count))
            } catch (e: Exception) {
                // Skip malformed items
            }
        }

        return builder.toString()
    }

    private fun formatTimeData(json: String): String {
        if (json.trim() == "[]") return "No time tracking data available"

        val builder = StringBuilder()
        builder.append(String.format("%-15s %12s %8s %10s\n", "Category", "Total Hours", "Count", "Avg Hours"))
        builder.append("-".repeat(50) + "\n")

        val items = json.trim().removePrefix("[").removeSuffix("]").split("},")
        for (item in items) {
            try {
                val category = extractJsonValue(item, "category")
                val totalHours = extractJsonValue(item, "totalDurationHours").toDoubleOrNull() ?: 0.0
                val count = extractJsonValue(item, "activityCount")
                val avgHours = extractJsonValue(item, "averageDurationHours").toDoubleOrNull() ?: 0.0

                builder.append(String.format("%-15s %12.2f %8s %10.2f\n", category, totalHours, count, avgHours))
            } catch (e: Exception) {
                // Skip malformed items
            }
        }

        return builder.toString()
    }

    private fun formatCalendarData(json: String): String {
        val builder = StringBuilder()
        
        try {
            val total = extractJsonValue(json, "totalEvents")
            val upcoming = extractJsonValue(json, "upcomingEvents")
            val past = extractJsonValue(json, "pastEvents")
            val allDay = extractJsonValue(json, "allDayEvents")

            builder.append("Total Events:    $total\n")
            builder.append("Upcoming:        $upcoming\n")
            builder.append("Past:            $past\n")
            builder.append("All-Day Events:  $allDay\n")
        } catch (e: Exception) {
            return "Error parsing calendar data"
        }

        return builder.toString()
    }

    private fun extractJsonValue(json: String, key: String): String {
        val pattern = "\"$key\"\\s*:\\s*\"?([^,}\"]+)\"?".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.trim() ?: ""
    }
}
