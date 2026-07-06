package org.jarvis.desktop.features.finance

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
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
 * Finance draft review inbox — batch-parses raw bank notifications (one per
 * line) into low/medium-confidence drafts and lets the owner approve
 * (persist as a real expense), reject (drop), or edit-then-approve each one.
 *
 * See [FinanceReviewReadModel] for why this queue lives entirely in memory
 * rather than being paged from a server-side table.
 */
class FinanceReviewView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = FinanceReviewReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-finance-review").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    /** The in-memory review queue — the only place these drafts exist until approved. */
    private var pendingDrafts: List<FinanceReviewReadModel.Draft> = emptyList()

    private val statusPill = ShellPanelSupport.statusPill("Review")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "Paste raw bank push notifications (one per line). HIGH-confidence ones are auto-stored by the backend; the rest land here for review."
    )

    private val batchField = TextArea().apply {
        promptText = "Покупка 45,00 zł w Lidl karta *1234\nPłatność 12.30 EUR Uber ****5678"
        isWrapText = true
        prefRowCount = 4
    }
    private val parseButton = Button("Parse batch").apply {
        styleClass += "shell-action-button"
        setOnAction { parseBatch() }
    }
    private val parseResult = ShellPanelSupport.sectionSubtitle("")

    private val draftsContainer = VBox(10.0)

    private val inboxStatusLabel = ShellPanelSupport.sectionSubtitle(
        "Drafts persisted server-side (FINANCE-REVIEW) — edit amount/merchant/category, then approve or reject."
    )
    private val inboxRefreshButton = Button("Refresh inbox").apply {
        styleClass += "shell-action-button"
        setOnAction { loadInbox() }
    }
    private val inboxContainer = VBox(10.0)

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-finance-review-view"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
        renderPlaceholder("Parse a batch above to populate the review queue.")
        renderInboxPlaceholder("Refresh to load the persisted review inbox.")
    }

    override fun onRouteActivated() {
        loadInbox()
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(6.0).apply {
                children += Label("Finance Review Inbox").apply { styleClass += "shell-page-title" }
                children += Label("Review low/medium-confidence bank-notification drafts before they become expenses.").apply {
                    styleClass += "shell-page-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += statusPill
        }

        val parserCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Batch parser")
            children += statusLabel
            children += batchField
            children += HBox(12.0, parseButton).apply { alignment = Pos.CENTER_LEFT }
            children += parseResult
        }

        val queueCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Review queue")
            children += draftsContainer
        }

        val inboxCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += ShellPanelSupport.sectionTitle("Persisted review inbox")
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                children += inboxRefreshButton
            }
            children += inboxStatusLabel
            children += inboxContainer
        }

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, parserCard, queueCard, inboxCard)
        }
    }

    private fun parseBatch() {
        val csv = batchField.text?.trim().orEmpty()
        if (csv.isBlank()) {
            parseResult.text = "Paste at least one notification first."
            return
        }
        runAction("Parsing batch…") {
            val result = readModel.parseBatch(csv)
            Platform.runLater {
                pendingDrafts = result.drafts
                renderDrafts()
                parseResult.text = "Imported ${result.imported}/${result.totalRows} automatically; " +
                    "${result.drafts.size} need review."
            }
        }
    }

    private fun runAction(progress: String, block: () -> Unit) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        parseButton.isDisable = true
        statusPill.text = "Working"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = progress

        worker.execute {
            try {
                block()
                Platform.runLater {
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    parseResult.text = e.message ?: "Finance review request failed."
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { parseButton.isDisable = false }
            }
        }
    }

    private fun renderDrafts() {
        if (pendingDrafts.isEmpty()) {
            renderPlaceholder("No drafts awaiting review.")
            return
        }
        draftsContainer.children.setAll(pendingDrafts.mapIndexed { index, draft -> draftCard(index, draft) })
    }

    private fun draftCard(index: Int, draft: FinanceReviewReadModel.Draft): Node {
        return VBox(6.0).apply {
            styleClass += "shell-section-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label("${draft.amount} ${draft.currency} · ${draft.merchant.ifBlank { "unknown merchant" }}").apply {
                    styleClass += "shell-section-title"
                }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                val pill = ShellPanelSupport.statusPill(draft.confidence)
                ShellPanelSupport.applyTone(
                    pill,
                    if (draft.confidence == "MEDIUM") "shell-status-tone-warning" else "shell-status-tone-error"
                )
                children += pill
            }
            children += Label("category: ${draft.category} · card: ${draft.cardMask.ifBlank { "n/a" }}").apply {
                styleClass += "shell-section-subtitle"
            }
            if (draft.notes.isNotEmpty()) {
                children += Label(draft.notes.joinToString("; ")).apply {
                    isWrapText = true
                    styleClass += "shell-section-subtitle"
                }
            }
            children += HBox(8.0).apply {
                alignment = Pos.CENTER_RIGHT
                children += Button("Edit").apply {
                    styleClass += "shell-action-button"
                    setOnAction { beginEdit(index) }
                }
                children += Button("Reject").apply {
                    styleClass += "shell-action-button-danger"
                    setOnAction { reject(index) }
                }
                children += Button("Approve").apply {
                    styleClass += "shell-action-button"
                    setOnAction { approve(index) }
                }
            }
        }
    }

    private fun beginEdit(index: Int) {
        val draft = pendingDrafts.getOrNull(index) ?: return
        val dialog = Dialog<ButtonType>()
        dialog.title = "Edit draft transaction"
        dialog.headerText = "Editing ${draft.merchant.ifBlank { "transaction" }}"

        val amountField = TextField(draft.amount)
        val currencyField = TextField(draft.currency)
        val categoryField = TextField(draft.category)
        val merchantField = TextField(draft.merchant)
        dialog.dialogPane.content = VBox(10.0).apply {
            padding = Insets(12.0)
            children += Label("Amount")
            children += amountField
            children += Label("Currency")
            children += currencyField
            children += Label("Category")
            children += categoryField
            children += Label("Merchant / description")
            children += merchantField
        }
        val saveButton = ButtonType("Save", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.setAll(saveButton, ButtonType.CANCEL)

        dialog.showAndWait().ifPresent { result ->
            if (result == saveButton) {
                val edited = draft.copy(
                    amount = amountField.text.trim(),
                    currency = currencyField.text.trim(),
                    category = categoryField.text.trim(),
                    merchant = merchantField.text.trim()
                )
                pendingDrafts = pendingDrafts.toMutableList().also { it[index] = edited }
                renderDrafts()
            }
        }
    }

    private fun reject(index: Int) {
        pendingDrafts = pendingDrafts.filterIndexed { i, _ -> i != index }
        renderDrafts()
        parseResult.text = "Draft rejected — nothing was persisted."
    }

    private fun approve(index: Int) {
        val draft = pendingDrafts.getOrNull(index) ?: return
        runAction("Approving draft…") {
            readModel.approve(draft)
            Platform.runLater {
                pendingDrafts = pendingDrafts.filterIndexed { i, _ -> i != index }
                renderDrafts()
                parseResult.text = "Approved and stored as an expense."
            }
        }
    }

    private fun renderPlaceholder(message: String) {
        draftsContainer.children.setAll(
            VBox(6.0).apply {
                styleClass.addAll("shell-section-card", "shell-placeholder")
                children += Label(message).apply {
                    styleClass += "shell-placeholder-body"
                    isWrapText = true
                }
            }
        )
    }

    private fun loadInbox() {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        inboxRefreshButton.isDisable = true
        inboxStatusLabel.text = "Loading persisted drafts…"

        worker.execute {
            try {
                val page = readModel.listInbox()
                Platform.runLater {
                    renderInbox(page)
                    inboxStatusLabel.text = "${page.items.size} draft(s) awaiting review (page ${page.page + 1}/${maxOf(page.totalPages, 1)})."
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inboxStatusLabel.text = e.message ?: "Review inbox request failed."
                    renderInboxPlaceholder("Unable to load the review inbox.\n${e.message ?: "Unknown error"}")
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { inboxRefreshButton.isDisable = false }
            }
        }
    }

    private fun renderInbox(page: FinanceReviewReadModel.InboxPage) {
        if (page.items.isEmpty()) {
            renderInboxPlaceholder("No persisted drafts awaiting review.")
            return
        }
        inboxContainer.children.setAll(page.items.map(::inboxDraftCard))
    }

    private fun inboxDraftCard(draft: FinanceReviewReadModel.InboxDraft): Node {
        return VBox(6.0).apply {
            styleClass += "shell-section-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label("${draft.amount} ${draft.currency} · ${draft.merchant.ifBlank { "unknown merchant" }}").apply {
                    styleClass += "shell-section-title"
                }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                val pill = ShellPanelSupport.statusPill(draft.confidence)
                ShellPanelSupport.applyTone(
                    pill,
                    if (draft.confidence == "MEDIUM") "shell-status-tone-warning" else "shell-status-tone-error"
                )
                children += pill
            }
            children += Label("category: ${draft.category} · occurred: ${draft.occurredAt.ifBlank { "unknown" }}").apply {
                styleClass += "shell-section-subtitle"
            }
            if (draft.notes.isNotBlank()) {
                children += Label(draft.notes).apply {
                    isWrapText = true
                    styleClass += "shell-section-subtitle"
                }
            }
            children += HBox(8.0).apply {
                alignment = Pos.CENTER_RIGHT
                children += Button("Edit").apply {
                    styleClass += "shell-action-button"
                    setOnAction { beginEditInboxDraft(draft) }
                }
                children += Button("Reject").apply {
                    styleClass += "shell-action-button-danger"
                    setOnAction { rejectInboxDraft(draft.id) }
                }
                children += Button("Approve").apply {
                    styleClass += "shell-action-button"
                    setOnAction { approveInboxDraft(draft.id) }
                }
            }
        }
    }

    private fun beginEditInboxDraft(draft: FinanceReviewReadModel.InboxDraft) {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Edit review-inbox draft"
        dialog.headerText = "Editing ${draft.merchant.ifBlank { "transaction" }}"

        val amountField = TextField(draft.amount)
        val currencyField = TextField(draft.currency)
        val categoryField = TextField(draft.category)
        val merchantField = TextField(draft.merchant)
        dialog.dialogPane.content = VBox(10.0).apply {
            padding = Insets(12.0)
            children += Label("Amount")
            children += amountField
            children += Label("Currency")
            children += currencyField
            children += Label("Category")
            children += categoryField
            children += Label("Merchant / description")
            children += merchantField
        }
        val saveButton = ButtonType("Save", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.setAll(saveButton, ButtonType.CANCEL)

        dialog.showAndWait().ifPresent { result ->
            if (result == saveButton) {
                submitEditInboxDraft(
                    draft.id,
                    amountField.text,
                    merchantField.text,
                    categoryField.text,
                    currencyField.text
                )
            }
        }
    }

    private fun submitEditInboxDraft(id: Long, amount: String, merchant: String, category: String, currency: String) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        inboxStatusLabel.text = "Saving changes…"

        worker.execute {
            try {
                readModel.editInboxDraft(id, amount, merchant, category, currency)
                val page = readModel.listInbox()
                Platform.runLater {
                    renderInbox(page)
                    inboxStatusLabel.text = "Draft updated."
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inboxStatusLabel.text = e.message ?: "Failed to save draft."
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun approveInboxDraft(id: Long) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        inboxStatusLabel.text = "Approving draft…"

        worker.execute {
            try {
                val outcome = readModel.approveInboxDraft(id)
                val page = readModel.listInbox()
                Platform.runLater {
                    renderInbox(page)
                    inboxStatusLabel.text = if (outcome.duplicate) {
                        "Duplicate of an existing expense — no new row created (${outcome.expenseSummary})."
                    } else {
                        "Approved and stored as an expense (${outcome.expenseSummary})."
                    }
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inboxStatusLabel.text = e.message ?: "Failed to approve draft."
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun rejectInboxDraft(id: Long) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        inboxStatusLabel.text = "Rejecting draft…"

        worker.execute {
            try {
                readModel.rejectInboxDraft(id)
                val page = readModel.listInbox()
                Platform.runLater {
                    renderInbox(page)
                    inboxStatusLabel.text = "Draft rejected — nothing was persisted."
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inboxStatusLabel.text = e.message ?: "Failed to reject draft."
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun renderInboxPlaceholder(message: String) {
        inboxContainer.children.setAll(
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
