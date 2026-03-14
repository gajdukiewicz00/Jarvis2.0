package org.jarvis.desktop.ui.tabs

import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import kotlinx.serialization.json.Json
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.model.ExpenseDTO
import org.jarvis.desktop.service.LifeExpenseRequestFactory
import java.math.BigDecimal

class LifeTab(private val apiClient: ApiClient) {
    val tab = Tab("Life")
    private val statusLabel = Label("")
    private val expenseListView = javafx.scene.control.ListView<String>()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val expenseRequestFactory = LifeExpenseRequestFactory()

    init {
        val content = VBox(10.0)
        content.children.add(Label("Life Tracker"))
        
        // Status label for feedback
        statusLabel.style = "-fx-font-weight: bold;"
        content.children.add(statusLabel)

        // Finance - Add Expense Form
        content.children.add(Label("Add Expense:"))
        val financeBox = HBox(10.0)
        val amountField = TextField()
        amountField.promptText = "Amount (EUR)"
        amountField.prefWidth = 100.0
        
        val categoryCombo = javafx.scene.control.ComboBox<String>()
        categoryCombo.items.addAll("Food", "Transport", "Shopping", "Entertainment", "Bills", "Health", "General")
        categoryCombo.value = "General"
        categoryCombo.prefWidth = 120.0
        
        val descField = TextField()
        descField.promptText = "Description"
        descField.prefWidth = 200.0
        
        val addExpenseBtn = Button("Add Expense")
        addExpenseBtn.setOnAction {
            val amount = amountField.text.toDoubleOrNull()
            val category = categoryCombo.value ?: "General"
            val description = descField.text
            
            if (amount != null) {
                try {
                    val requestBody = expenseRequestFactory.create(
                        amount = BigDecimal.valueOf(amount),
                        currency = "EUR",
                        category = category,
                        description = description,
                        userId = TokenManager.getUserId()
                    )
                    
                    apiClient.post("/life/finance/expense", requestBody)
                    statusLabel.text = "✓ Expense added: €$amount - $category"
                    statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
                    amountField.clear()
                    descField.clear()
                    loadExpenses() // Refresh the list
                } catch (e: Exception) {
                    statusLabel.text = "✗ Error: ${e.message ?: "Failed to add expense"}"
                    statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"
                }
            } else {
                statusLabel.text = "✗ Please enter a valid amount"
                statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"
            }
        }
        financeBox.children.addAll(amountField, categoryCombo, descField, addExpenseBtn)
        content.children.add(financeBox)

        // Recent Expenses List
        val expenseHeader = HBox(10.0)
        expenseHeader.children.add(Label("Recent Expenses:"))
        val refreshBtn = Button("Refresh")
        refreshBtn.setOnAction {
            loadExpenses()
        }
        expenseHeader.children.add(refreshBtn)
        content.children.add(expenseHeader)
        
        expenseListView.prefHeight = 150.0
        content.children.add(expenseListView)
        
        // Load expenses on init
        loadExpenses()

        // Time tracking section (keeping existing functionality)
        content.children.add(Label("Time Tracking:"))
        val timeBox = HBox(10.0)
        val startBtn = Button("Start Timer")
        startBtn.setOnAction {
            try {
                apiClient.post("/life/time/start", "{\"activity\": \"Work\", \"category\": \"Work\"}")
                statusLabel.text = "✓ Timer started"
                statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
            } catch (e: Exception) {
                statusLabel.text = "✗ Error: ${e.message ?: "Failed to start timer"}"
                statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"
            }
        }
        val stopBtn = Button("Stop Timer")
        stopBtn.setOnAction {
            try {
                apiClient.post("/life/time/stop", "{}")
                statusLabel.text = "✓ Timer stopped"
                statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
            } catch (e: Exception) {
                statusLabel.text = "✗ Error: ${e.message ?: "Failed to stop timer"}"
                statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"
            }
        }
        timeBox.children.addAll(startBtn, stopBtn)
        content.children.add(timeBox)

        tab.content = content
        tab.isClosable = false
    }
    
    private fun loadExpenses() {
        try {
            val response = apiClient.get("/life/finance/expenses")
            expenseListView.items.clear()
            
            // Type-safe JSON parsing with kotlinx.serialization
            val expenses = json.decodeFromString<List<ExpenseDTO>>(response)
            
            // Display most recent 10 expenses
            expenses.reversed().take(10).forEach { expense ->
                val displayText = "${expense.currency}${expense.amount} - ${expense.category} - ${expense.description ?: "N/A"}"
                expenseListView.items.add(displayText)
            }
            
            statusLabel.text = "✓ Loaded ${expenseListView.items.size} expenses"
            statusLabel.style = "-fx-text-fill: green; -fx-font-weight: bold;"
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Unknown error"
            // Show user-friendly error message
            statusLabel.text = if (errorMessage.contains("Connection refused") || errorMessage.contains("not available")) {
                "✗ Server unavailable. Please start the API gateway (${AppConfig.apiGatewayBaseUrl})"
            } else {
                "✗ Error loading expenses: $errorMessage"
            }
            statusLabel.style = "-fx-text-fill: red; -fx-font-weight: bold;"
            expenseListView.items.clear()
            expenseListView.items.add("No data available - check server connection")
        }
    }
}
