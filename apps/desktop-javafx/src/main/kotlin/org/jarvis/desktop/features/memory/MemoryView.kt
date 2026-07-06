package org.jarvis.desktop.features.memory

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
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
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.features.common.ShellPanelSupport
import org.jarvis.desktop.shell.ShellRouteContent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Memory panel — unified semantic search over the RAG/Obsidian store plus a
 * recent-notes feed. Search hits `POST /api/v1/memory/search/unified`; the
 * notes feed hits `GET /api/v1/memory/notes`. Notes can also be edited
 * (`PUT /api/v1/memory/notes/{memoryId}`) or forgotten
 * (`DELETE /api/v1/memory/notes/{memoryId}`) in place.
 */
class MemoryView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = MemoryReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-memory").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    /** Last fetch used to populate [resultsContainer] — reused to refresh after edit/forget. */
    private var lastFetch: () -> List<MemoryReadModel.MemoryItem> = { readModel.recentNotes() }

    private val statusPill = ShellPanelSupport.statusPill("Memory")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "Semantic recall across pgvector memory and Obsidian notes. Search by meaning, not just keywords."
    )

    private val queryField = TextField().apply {
        promptText = "Search your memory (e.g. \"что я люблю пить\")"
        setOnAction { search() }
    }
    private val searchButton = Button("Search").apply {
        styleClass += "shell-action-button"
        setOnAction { search() }
    }
    private val recentButton = Button("Recent notes").apply {
        styleClass += "shell-action-button"
        setOnAction { loadRecent() }
    }
    private val scopeFilter = ComboBox<String>().apply {
        items.add("All scopes")
        items.addAll(MemoryReadModel.SCOPES)
        value = "All scopes"
        setOnAction { loadRecent() }
    }

    private val resultsContainer = VBox(12.0)

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-memory-view"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
        renderPlaceholder("Search above or load recent notes to populate this view.")
    }

    override fun onRouteActivated() {
        if (resultsContainer.children.size == 1) {
            loadRecent()
        }
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(6.0).apply {
                children += Label("Memory").apply { styleClass += "shell-page-title" }
                children += Label("Search and browse what Jarvis remembers.").apply {
                    styleClass += "shell-page-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += statusPill
        }

        val searchCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Unified search")
            children += statusLabel
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(queryField, Priority.ALWAYS)
                queryField.maxWidth = Double.MAX_VALUE
                children.addAll(queryField, searchButton, recentButton, scopeFilter)
            }
        }

        val resultsCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Results")
            children += resultsContainer
        }

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, searchCard, resultsCard)
        }
    }

    private fun search() {
        val query = queryField.text?.trim().orEmpty()
        if (query.isBlank()) {
            statusPill.text = "Input needed"
            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
            statusLabel.text = "Enter a search query first."
            return
        }
        run("Searching memory…") { readModel.search(query) }
    }

    private fun loadRecent() {
        val scope = scopeFilter.value?.takeIf { it != "All scopes" }
        run("Loading recent notes…") { readModel.recentNotes(scope = scope) }
    }

    private fun run(progress: String, fetch: () -> List<MemoryReadModel.MemoryItem>) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        lastFetch = fetch
        searchButton.isDisable = true
        recentButton.isDisable = true
        statusPill.text = "Loading"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = progress

        worker.execute {
            try {
                val items = fetch()
                Platform.runLater {
                    renderResults(items)
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    statusLabel.text = "${items.size} result(s)."
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Memory request failed."
                    renderPlaceholder("Память временно недоступна.\n${e.message ?: "Unknown error"}")
                }
            } finally {
                inFlight.set(false)
                Platform.runLater {
                    searchButton.isDisable = false
                    recentButton.isDisable = false
                }
            }
        }
    }

    private fun renderResults(items: List<MemoryReadModel.MemoryItem>) {
        if (items.isEmpty()) {
            renderPlaceholder("No matching memories found.")
            return
        }
        resultsContainer.children.setAll(items.map(::itemCard))
    }

    private fun itemCard(item: MemoryReadModel.MemoryItem): Node {
        return VBox(6.0).apply {
            styleClass += "shell-section-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label(item.title).apply { styleClass += "shell-section-title" }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                val pill = ShellPanelSupport.statusPill(item.source)
                ShellPanelSupport.applyTone(pill, "shell-status-tone-info")
                children += pill
            }
            if (item.snippet.isNotBlank()) {
                children += Label(item.snippet).apply {
                    styleClass += "shell-section-subtitle"
                    isWrapText = true
                }
            }
            item.score?.let { score ->
                children += Label("relevance ${"%.3f".format(score)}").apply {
                    styleClass += "shell-section-subtitle"
                }
            }
            item.scope?.let { scope ->
                children += Label("scope: $scope${if (item.pinned) " · pinned" else ""}").apply {
                    styleClass += "shell-section-subtitle"
                }
            }
            if (item.isManageable) {
                children += HBox(8.0).apply {
                    alignment = Pos.CENTER_RIGHT
                    children += Button("Why?").apply {
                        styleClass += "shell-action-button"
                        setOnAction { showWhy(item) }
                    }
                    children += Button("Scope…").apply {
                        styleClass += "shell-action-button"
                        setOnAction { beginChangeScope(item) }
                    }
                    children += Button(if (item.pinned) "Unpin" else "Pin").apply {
                        styleClass += "shell-action-button"
                        setOnAction { togglePin(item) }
                    }
                    children += Button("Edit").apply {
                        styleClass += "shell-action-button"
                        setOnAction { beginEdit(item) }
                    }
                    children += Button("Forget").apply {
                        styleClass += "shell-action-button"
                        setOnAction { beginForget(item) }
                    }
                }
            }
        }
    }

    private fun beginEdit(item: MemoryReadModel.MemoryItem) {
        val memoryId = item.memoryId ?: return
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusPill.text = "Loading"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "Loading note for edit…"

        worker.execute {
            try {
                val detail = readModel.getNote(memoryId)
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    showEditDialog(detail)
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to load note."
                }
            }
        }
    }

    private fun showEditDialog(detail: MemoryReadModel.NoteDetail) {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Edit memory note"
        dialog.headerText = "Editing \"${detail.title}\""

        val titleField = TextField(detail.title)
        val bodyArea = TextArea(detail.body).apply {
            isWrapText = true
            prefRowCount = 10
            prefColumnCount = 40
        }
        dialog.dialogPane.content = VBox(10.0).apply {
            padding = Insets(12.0)
            children += Label("Title")
            children += titleField
            children += Label("Content")
            children += bodyArea
        }
        val saveButton = ButtonType("Save", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.setAll(saveButton, ButtonType.CANCEL)

        dialog.showAndWait().ifPresent { result ->
            if (result == saveButton) {
                submitEdit(detail.memoryId, titleField.text, bodyArea.text)
            }
        }
    }

    private fun submitEdit(memoryId: String, title: String, body: String) {
        if (title.isBlank()) {
            statusPill.text = "Input needed"
            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
            statusLabel.text = "Title cannot be empty."
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusPill.text = "Saving"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "Saving changes…"

        worker.execute {
            try {
                readModel.updateNote(memoryId, title, body)
                Platform.runLater {
                    inFlight.set(false)
                    run("Refreshing…", lastFetch)
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to save note."
                }
            }
        }
    }

    private fun showWhy(item: MemoryReadModel.MemoryItem) {
        val memoryId = item.memoryId ?: return
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusPill.text = "Loading"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "Loading provenance for \"${item.title}\"…"

        worker.execute {
            try {
                val info = readModel.why(memoryId)
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    renderWhyDialog(item.title, info)
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to load provenance."
                }
            }
        }
    }

    private fun renderWhyDialog(title: String, info: MemoryReadModel.WhyInfo) {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Why does Jarvis remember this?"
        dialog.headerText = title
        dialog.dialogPane.content = VBox(6.0).apply {
            padding = Insets(12.0)
            children += Label("Source: ${info.source ?: "unknown"}")
            children += Label("Privacy: ${info.privacy ?: "unknown"}")
            children += Label("Scope: ${info.scope ?: "unknown"}")
            children += Label("Pinned: ${if (info.pinned) "yes" else "no"}")
            children += Label("Confidence: ${info.confidence?.let { "%.2f".format(it) } ?: "n/a"}")
            children += Label("Created: ${info.createdAt ?: "unknown"}")
            if (!info.explanation.isNullOrBlank()) {
                children += Label(info.explanation).apply { isWrapText = true }
            }
        }
        dialog.dialogPane.buttonTypes.setAll(ButtonType.CLOSE)
        dialog.showAndWait()
    }

    private fun togglePin(item: MemoryReadModel.MemoryItem) {
        val memoryId = item.memoryId ?: return
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        val pinning = !item.pinned
        statusPill.text = if (pinning) "Pinning" else "Unpinning"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "${if (pinning) "Pinning" else "Unpinning"} \"${item.title}\"…"

        worker.execute {
            try {
                if (pinning) readModel.pinNote(memoryId) else readModel.unpinNote(memoryId)
                Platform.runLater {
                    inFlight.set(false)
                    run("Refreshing…", lastFetch)
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to update pin state."
                }
            }
        }
    }

    private fun beginChangeScope(item: MemoryReadModel.MemoryItem) {
        val memoryId = item.memoryId ?: return
        val dialog = Dialog<ButtonType>()
        dialog.title = "Change scope"
        dialog.headerText = "Move \"${item.title}\" to a new scope"

        val scopeCombo = ComboBox<String>().apply {
            items.addAll(MemoryReadModel.SCOPES)
            value = item.scope?.takeIf { it in MemoryReadModel.SCOPES } ?: MemoryReadModel.SCOPES.first()
        }
        dialog.dialogPane.content = VBox(10.0).apply {
            padding = Insets(12.0)
            children += Label("Scope")
            children += scopeCombo
        }
        val applyButton = ButtonType("Apply", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.setAll(applyButton, ButtonType.CANCEL)

        dialog.showAndWait().ifPresent { result ->
            if (result == applyButton) {
                submitChangeScope(memoryId, scopeCombo.value ?: MemoryReadModel.SCOPES.first())
            }
        }
    }

    private fun submitChangeScope(memoryId: String, scope: String) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusPill.text = "Saving"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "Changing scope to $scope…"

        worker.execute {
            try {
                readModel.changeScope(memoryId, scope)
                Platform.runLater {
                    inFlight.set(false)
                    run("Refreshing…", lastFetch)
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to change scope."
                }
            }
        }
    }

    private fun beginForget(item: MemoryReadModel.MemoryItem) {
        val memoryId = item.memoryId ?: return
        val reason = promptForgetReason(item.title) ?: return
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusPill.text = "Forgetting"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
        statusLabel.text = "Forgetting \"${item.title}\"…"

        worker.execute {
            try {
                readModel.forgetNote(memoryId, TokenManager.getUsername(), reason)
                Platform.runLater {
                    inFlight.set(false)
                    run("Refreshing…", lastFetch)
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to forget note."
                }
            }
        }
    }

    /**
     * HIGH-risk confirmation for "Jarvis, forget this". Returns the reason the
     * user entered (may be blank) if they confirmed, or `null` if cancelled.
     */
    private fun promptForgetReason(title: String): String? {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Forget memory note"
        dialog.headerText = "Forget \"$title\"?"

        val reasonField = TextField().apply { promptText = "Reason (optional)" }
        dialog.dialogPane.content = VBox(10.0).apply {
            padding = Insets(12.0)
            children += Label(
                "This soft-deletes the note (content wiped, tombstoned in Obsidian) " +
                    "and cannot be undone from this UI."
            ).apply { isWrapText = true }
            children += Label("Reason")
            children += reasonField
        }
        val forgetButton = ButtonType("Forget", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.setAll(forgetButton, ButtonType.CANCEL)

        val confirmed = dialog.showAndWait().orElse(ButtonType.CANCEL) == forgetButton
        return if (confirmed) reasonField.text else null
    }

    private fun renderPlaceholder(message: String) {
        resultsContainer.children.setAll(
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
