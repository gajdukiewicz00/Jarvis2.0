package org.jarvis.desktop.features.memory

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
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
 * Memory panel — unified semantic search over the RAG/Obsidian store plus a
 * recent-notes feed. Search hits `POST /api/v1/memory/search/unified`; the
 * notes feed hits `GET /api/v1/memory/notes`.
 */
class MemoryView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = MemoryReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-memory").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

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
                children.addAll(queryField, searchButton, recentButton)
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
        run("Loading recent notes…") { readModel.recentNotes() }
    }

    private fun run(progress: String, fetch: () -> List<MemoryReadModel.MemoryItem>) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
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
