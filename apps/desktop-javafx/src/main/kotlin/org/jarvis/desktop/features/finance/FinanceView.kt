package org.jarvis.desktop.features.finance

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.features.common.ShellPanelSupport
import org.jarvis.desktop.shell.ShellRouteContent
import java.math.BigDecimal
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finance panel — bank-notification parser, recent transactions, and a manual
 * add-expense form, all backed by the life-tracker finance endpoints.
 */
class FinanceView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = FinanceReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-finance").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Finance")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "Parse a bank SMS/push to auto-categorise a spend, review recent transactions, or log an expense by hand."
    )

    private val notificationField = TextArea().apply {
        promptText = "Paste a bank notification (e.g. \"Покупка 540 RUB в COFFEE HOUSE\")"
        isWrapText = true
        prefRowCount = 3
    }
    private val parseButton = Button("Parse notification").apply {
        styleClass += "shell-action-button"
        setOnAction { parseNotification() }
    }
    private val parseResult = ShellPanelSupport.sectionSubtitle("")

    private val amountField = TextField().apply { promptText = "Amount" }
    private val currencyField = TextField("RUB").apply { promptText = "Currency" }
    private val categoryField = TextField().apply { promptText = "Category" }
    private val descriptionField = TextField().apply { promptText = "Description (optional)" }
    private val addButton = Button("Add expense").apply {
        styleClass += "shell-action-button"
        setOnAction { addExpense() }
    }
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { loadTransactions() }
    }

    private val transactionsContainer = VBox(10.0)

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-finance-view"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
        renderPlaceholder("Refresh to load recent transactions.")
    }

    override fun onRouteActivated() {
        loadTransactions()
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(6.0).apply {
                children += Label("Finance").apply { styleClass += "shell-page-title" }
                children += Label("Track spending and parse bank notifications.").apply {
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

        val parserCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Bank notification parser")
            children += statusLabel
            children += notificationField
            children += HBox(12.0, parseButton).apply { alignment = Pos.CENTER_LEFT }
            children += parseResult
        }

        val addCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Add expense")
            children += FlowPane(12.0, 12.0).apply {
                children.addAll(amountField, currencyField, categoryField, descriptionField, addButton)
            }
        }

        val txCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Recent transactions")
            children += transactionsContainer
        }

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, parserCard, addCard, txCard)
        }
    }

    private fun parseNotification() {
        val text = notificationField.text?.trim().orEmpty()
        if (text.isBlank()) {
            statusLabel.text = "Paste a bank notification first."
            return
        }
        runAction("Parsing notification…") {
            val parsed = readModel.parseNotification(text)
            Platform.runLater {
                parseResult.text = "→ ${parsed.amount} ${parsed.currency} · ${parsed.category}" +
                    (if (parsed.merchant.isNotBlank()) " · ${parsed.merchant}" else "")
                amountField.text = parsed.amount.filter { it.isDigit() || it == '.' }
                currencyField.text = parsed.currency.ifBlank { currencyField.text }
                categoryField.text = parsed.category
                if (parsed.merchant.isNotBlank()) descriptionField.text = parsed.merchant
            }
        }
    }

    private fun addExpense() {
        val amount = amountField.text?.trim()?.toBigDecimalOrNull()
        if (amount == null) {
            statusPill.text = "Input needed"
            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
            statusLabel.text = "Enter a valid numeric amount."
            return
        }
        val currency = currencyField.text?.trim()?.ifBlank { "RUB" } ?: "RUB"
        val category = categoryField.text?.trim()?.ifBlank { "uncategorized" } ?: "uncategorized"
        val description = descriptionField.text?.trim()
        runAction("Saving expense…") {
            readModel.addExpense(amount, currency, category, description)
            val transactions = readModel.listTransactions()
            Platform.runLater {
                amountField.clear()
                descriptionField.clear()
                renderTransactions(transactions)
            }
        }
    }

    private fun loadTransactions() {
        runAction("Loading transactions…") {
            val transactions = readModel.listTransactions()
            Platform.runLater { renderTransactions(transactions) }
        }
    }

    private fun runAction(progress: String, block: () -> Unit) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        setBusy(true)
        statusPill.text = "Working"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = progress

        worker.execute {
            try {
                block()
                Platform.runLater {
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    statusLabel.text = "Done."
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Finance request failed."
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { setBusy(false) }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        parseButton.isDisable = busy
        addButton.isDisable = busy
        refreshButton.isDisable = busy
    }

    private fun renderTransactions(transactions: List<FinanceReadModel.Transaction>) {
        if (transactions.isEmpty()) {
            renderPlaceholder("No transactions recorded yet.")
            return
        }
        transactionsContainer.children.setAll(transactions.map(::transactionCard))
    }

    private fun transactionCard(tx: FinanceReadModel.Transaction): Node {
        return VBox(4.0).apply {
            styleClass += "shell-section-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label("${tx.amount} ${tx.currency}").apply { styleClass += "shell-section-title" }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                val pill = ShellPanelSupport.statusPill(tx.category)
                ShellPanelSupport.applyTone(pill, "shell-status-tone-info")
                children += pill
            }
            val meta = listOfNotNull(
                tx.description.takeIf { it.isNotBlank() },
                tx.createdAt.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                children += Label(meta).apply {
                    styleClass += "shell-section-subtitle"
                    isWrapText = true
                }
            }
        }
    }

    private fun renderPlaceholder(message: String) {
        transactionsContainer.children.setAll(
            VBox(6.0).apply {
                styleClass.addAll("shell-section-card", "shell-placeholder")
                children += Label(message).apply {
                    styleClass += "shell-placeholder-body"
                    isWrapText = true
                }
            }
        )
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        runCatching { BigDecimal(this.replace(',', '.')) }.getOrNull()
}
