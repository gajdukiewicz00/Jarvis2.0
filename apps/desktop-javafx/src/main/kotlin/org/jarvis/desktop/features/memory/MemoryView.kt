package org.jarvis.desktop.features.memory

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.features.common.ShellPanelSupport
import org.jarvis.desktop.shell.ShellRouteContent
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Memory panel — unified semantic search over the RAG/Obsidian store plus a
 * recent-notes feed. Search hits `POST /api/v1/memory/search/unified`; the
 * notes feed hits `GET /api/v1/memory/notes`. Notes can also be edited
 * (`PUT /api/v1/memory/notes/{memoryId}`) or forgotten
 * (`DELETE /api/v1/memory/notes/{memoryId}`) in place.
 *
 * Also supports, all additive on top of the above:
 *  - client-side filtering of whatever is currently displayed by note source (the scope
 *    filter already re-queries the backend; source has no server-side query param).
 *  - encrypted (or plaintext-fallback) bulk export/import with a conflict-mode selector
 *    (`GET .../export/encrypted-or-plain`, `POST .../import/resolve`).
 *  - multi-select bulk "forget" and a "forget by query" sweep
 *    (`DELETE .../by-query`).
 */
class MemoryView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private companion object {
        private const val ALL_SOURCES = "All sources"
        private const val ALL_SCOPES = "All scopes"
    }

    private val readModel = MemoryReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-memory").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    /** Last fetch used to populate [resultsContainer] — reused to refresh after edit/forget. */
    private var lastFetch: () -> List<MemoryReadModel.MemoryItem> = { readModel.recentNotes() }

    /** Full result set from the last fetch, before the client-side source filter is applied. */
    private var lastItems: List<MemoryReadModel.MemoryItem> = emptyList()

    /** The subset of [lastItems] actually rendered (after the source filter) — what "select all" selects. */
    private var visibleItems: List<MemoryReadModel.MemoryItem> = emptyList()

    /** memoryIds checked via the bulk-select checkboxes, across filter changes. */
    private val selectedMemoryIds = LinkedHashSet<String>()

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
        items.add(ALL_SCOPES)
        items.addAll(MemoryReadModel.SCOPES)
        value = ALL_SCOPES
        setOnAction { loadRecent() }
    }

    /** Client-side filter over [lastItems] — the backend has no `?source=` query param. */
    private val sourceFilter = ComboBox<String>().apply {
        items.add(ALL_SOURCES)
        value = ALL_SOURCES
        setOnAction { renderFilteredResults() }
    }

    private val resultsContainer = VBox(12.0)

    private val selectionSummaryLabel = ShellPanelSupport.sectionSubtitle("0 selected")
    private val selectAllButton = Button("Select all").apply {
        styleClass += "shell-action-button"
        setOnAction { selectAllVisible() }
    }
    private val clearSelectionButton = Button("Clear selection").apply {
        styleClass += "shell-action-button"
        setOnAction { clearSelection() }
    }
    private val deleteSelectedButton = Button("Delete selected").apply {
        styleClass += "shell-action-button"
        isDisable = true
        setOnAction { beginBulkDelete() }
    }

    private val importModeCombo = ComboBox<String>().apply {
        items.addAll(MemoryReadModel.IMPORT_CONFLICT_MODES)
        value = MemoryReadModel.IMPORT_CONFLICT_MODES.first()
    }
    private val exportButton = Button("Export (encrypted)").apply {
        styleClass += "shell-action-button"
        setOnAction { beginExport() }
    }
    private val importButton = Button("Import…").apply {
        styleClass += "shell-action-button"
        setOnAction { beginImport() }
    }

    private val forgetQueryField = TextField().apply {
        promptText = "Forget notes matching a query (e.g. \"old grocery list\")"
        setOnAction { beginForgetByQuery() }
    }
    private val forgetScopeCombo = ComboBox<String>().apply {
        items.add(ALL_SCOPES)
        items.addAll(MemoryReadModel.SCOPES)
        value = ALL_SCOPES
    }
    private val forgetByQueryButton = Button("Forget by query").apply {
        styleClass += "shell-action-button"
        setOnAction { beginForgetByQuery() }
    }

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
                children.addAll(queryField, searchButton, recentButton, scopeFilter, sourceFilter)
            }
        }

        val bulkCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Bulk actions & data")
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += selectAllButton
                children += clearSelectionButton
                children += selectionSummaryLabel
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                children += deleteSelectedButton
            }
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += exportButton
                children += Label("Import conflict mode:").apply { styleClass += "shell-section-subtitle" }
                children += importModeCombo
                children += importButton
            }
            children += HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(forgetQueryField, Priority.ALWAYS)
                forgetQueryField.maxWidth = Double.MAX_VALUE
                children.addAll(forgetQueryField, forgetScopeCombo, forgetByQueryButton)
            }
        }

        val resultsCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Results")
            children += resultsContainer
        }

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, searchCard, bulkCard, resultsCard)
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
        val scope = scopeFilter.value?.takeIf { it != ALL_SCOPES }
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
                    lastItems = items
                    selectedMemoryIds.clear()
                    refreshSourceFilterOptions(items)
                    renderFilteredResults()
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

    /** Recomputes the client-side source filter over [lastItems] and re-renders. */
    private fun renderFilteredResults() {
        val selectedSource = sourceFilter.value?.takeIf { it != ALL_SOURCES }
        val filtered = if (selectedSource == null) lastItems else lastItems.filter { it.source == selectedSource }
        renderResults(filtered)
    }

    /** Repopulates [sourceFilter]'s options from the distinct sources in a fresh fetch. */
    private fun refreshSourceFilterOptions(items: List<MemoryReadModel.MemoryItem>) {
        val sources = items.map { it.source }.distinct().sorted()
        val previousSelection = sourceFilter.value
        sourceFilter.items.setAll(listOf(ALL_SOURCES) + sources)
        sourceFilter.value = previousSelection?.takeIf { it in sourceFilter.items } ?: ALL_SOURCES
    }

    private fun renderResults(items: List<MemoryReadModel.MemoryItem>) {
        visibleItems = items
        if (items.isEmpty()) {
            renderPlaceholder("No matching memories found.")
            updateSelectionSummary()
            return
        }
        resultsContainer.children.setAll(items.map(::itemCard))
        updateSelectionSummary()
    }

    private fun updateSelectionSummary() {
        selectionSummaryLabel.text = "${selectedMemoryIds.size} selected"
        deleteSelectedButton.isDisable = selectedMemoryIds.isEmpty()
    }

    private fun selectAllVisible() {
        visibleItems.forEach { item ->
            val memoryId = item.memoryId
            if (item.isManageable && memoryId != null) {
                selectedMemoryIds += memoryId
            }
        }
        renderFilteredResults()
    }

    private fun clearSelection() {
        selectedMemoryIds.clear()
        renderFilteredResults()
    }

    private fun itemCard(item: MemoryReadModel.MemoryItem): Node {
        return VBox(6.0).apply {
            styleClass += "shell-section-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                val memoryId = item.memoryId
                if (item.isManageable && memoryId != null) {
                    children += CheckBox().apply {
                        isSelected = memoryId in selectedMemoryIds
                        setOnAction {
                            if (isSelected) selectedMemoryIds += memoryId else selectedMemoryIds -= memoryId
                            updateSelectionSummary()
                        }
                    }
                }
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
                    val edited = MemoryDialogs.showEditDialog(detail)
                    if (edited != null) {
                        submitEdit(detail.memoryId, edited.first, edited.second)
                    }
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
                    MemoryDialogs.showWhyDialog(item.title, info)
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
        val scope = MemoryDialogs.promptScopeChange(item.title, item.scope) ?: return
        submitChangeScope(memoryId, scope)
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
        val reason = MemoryDialogs.promptForgetReason(item.title) ?: return
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

    /** Bulk "forget" for every checked row — same soft-delete as [beginForget], one confirmation for the batch. */
    private fun beginBulkDelete() {
        val memoryIds = selectedMemoryIds.toList()
        if (memoryIds.isEmpty()) {
            return
        }
        val reason = MemoryDialogs.promptForgetReason("${memoryIds.size} selected note(s)") ?: return
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusPill.text = "Forgetting"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
        statusLabel.text = "Forgetting ${memoryIds.size} selected note(s)…"

        worker.execute {
            try {
                val result = readModel.forgetMany(memoryIds, TokenManager.getUsername(), reason)
                Platform.runLater {
                    inFlight.set(false)
                    selectedMemoryIds.clear()
                    val failedNote = if (result.failed.isEmpty()) "" else " (${result.failed.size} failed)"
                    run("Forgot ${result.forgotten.size} of ${result.requested} note(s)$failedNote. Refreshing…", lastFetch)
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to forget selected notes."
                }
            }
        }
    }

    /**
     * Roadmap #11 — "forget by query": one confirmation, then a single backend sweep that
     * soft-deletes every ACTIVE note matching [forgetQueryField]/[forgetScopeCombo].
     */
    private fun beginForgetByQuery() {
        val query = forgetQueryField.text?.trim().orEmpty()
        val scope = forgetScopeCombo.value?.takeIf { it != ALL_SCOPES }
        if (query.isBlank() && scope == null) {
            statusPill.text = "Input needed"
            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
            statusLabel.text = "Enter a query or choose a scope to forget by."
            return
        }
        val target = if (query.isNotBlank()) "notes matching \"$query\"" else "all notes in scope $scope"
        val reason = MemoryDialogs.promptForgetReason(target) ?: return
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusPill.text = "Forgetting"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
        statusLabel.text = "Forgetting $target…"

        val queryParam: String? = if (query.isBlank()) null else query
        worker.execute {
            try {
                val result = readModel.forgetByQuery(
                    queryParam,
                    scope,
                    TokenManager.getUsername(),
                    reason
                )
                Platform.runLater {
                    inFlight.set(false)
                    forgetQueryField.clear()
                    run("Forgot ${result.count} $target. Refreshing…", lastFetch)
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to forget by query."
                }
            }
        }
    }

    /** Encrypted (or plaintext-fallback) export — fetches the takeout, then prompts to save it to disk. */
    private fun beginExport() {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        val scope = scopeFilter.value?.takeIf { it != ALL_SCOPES }
        statusPill.text = "Exporting"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "Preparing memory export…"

        worker.execute {
            try {
                val payload = readModel.exportEncryptedOrPlain(scope)
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    saveExportToFile(payload)
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to export memory notes."
                }
            }
        }
    }

    private fun saveExportToFile(payload: String) {
        val chooser = FileChooser().apply {
            title = "Save memory export"
            initialFileName = "jarvis-memory-export-${System.currentTimeMillis()}.json"
            extensionFilters.add(FileChooser.ExtensionFilter("JSON", "*.json"))
        }
        val target: File? = chooser.showSaveDialog(scene?.window)
        if (target != null) {
            target.writeText(payload)
            statusLabel.text = "Saved ${target.name}."
        } else {
            statusLabel.text = "Export ready — save cancelled."
        }
    }

    /** Pick a file previously produced by [beginExport] (or a raw `/export*` response) and bulk-import it. */
    private fun beginImport() {
        val chooser = FileChooser().apply {
            title = "Import memory export"
            extensionFilters.add(FileChooser.ExtensionFilter("JSON", "*.json"))
        }
        val source: File = chooser.showOpenDialog(scene?.window) ?: return
        val content = try {
            source.readText()
        } catch (e: Exception) {
            statusPill.text = "Unavailable"
            ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
            statusLabel.text = "Failed to read ${source.name}: ${e.message}"
            return
        }
        submitImport(content, importModeCombo.value ?: MemoryReadModel.IMPORT_CONFLICT_MODES.first())
    }

    private fun submitImport(content: String, mode: String) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        statusPill.text = "Importing"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "Importing notes (mode: $mode)…"

        worker.execute {
            try {
                val result = readModel.importFile(content, mode)
                Platform.runLater {
                    inFlight.set(false)
                    val summary = "Imported: ${result.created} created, ${result.overwritten} overwritten, " +
                        "${result.skipped} skipped, ${result.failed} failed (of ${result.received})."
                    run("$summary Refreshing…", lastFetch)
                }
            } catch (e: Exception) {
                Platform.runLater {
                    inFlight.set(false)
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Failed to import memory notes."
                }
            }
        }
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
