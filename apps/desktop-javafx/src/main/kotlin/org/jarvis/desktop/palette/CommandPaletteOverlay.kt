package org.jarvis.desktop.palette

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/** A palette entry bundled with the action it triggers when chosen. */
data class CommandPaletteAction(
    val entry: PaletteEntry,
    val action: () -> Unit
)

/**
 * Searchable command palette overlay: a search field + filtered list of
 * every shell route plus a handful of key actions (panic, theme toggle...).
 *
 * Rendered as a scrim + card, meant to be stacked on top of the shell's
 * content host, the same way [org.jarvis.desktop.onboarding.OnboardingWizardView]
 * is. Purely UI + the pure [CommandPaletteFilter] — no network calls, so it
 * is safe to construct headlessly.
 */
class CommandPaletteOverlay(
    private val entries: List<CommandPaletteAction>,
    private val onClose: () -> Unit
) : StackPane() {

    private val searchField = TextField().apply {
        promptText = "Type a command or search…"
        styleClass += "command-palette-field"
    }

    private val resultsList = ListView<CommandPaletteAction>().apply {
        styleClass += "command-palette-list"
        prefHeight = 320.0
        placeholder = Label("No matches")
    }

    init {
        styleClass += "command-palette-scrim"

        val card = VBox(12.0).apply {
            styleClass += "command-palette-card"
            maxWidth = 560.0
            padding = Insets(18.0)
            children.addAll(searchField, resultsList)
        }
        StackPane.setAlignment(card, Pos.TOP_CENTER)
        StackPane.setMargin(card, Insets(72.0, 0.0, 0.0, 0.0))
        children += card

        resultsList.cellFactory = javafx.util.Callback {
            object : ListCell<CommandPaletteAction>() {
                override fun updateItem(item: CommandPaletteAction?, empty: Boolean) {
                    super.updateItem(item, empty)
                    styleClass += "command-palette-cell"
                    text = if (empty || item == null) null else "${item.entry.label}   ·   ${item.entry.category}"
                }
            }
        }

        resultsList.items.setAll(entries)

        searchField.textProperty().addListener { _, _, text ->
            resultsList.items.setAll(CommandPaletteFilter.filter(entries, text) { it.entry })
        }
        searchField.setOnKeyPressed { event ->
            when (event.code) {
                KeyCode.ESCAPE -> onClose()
                KeyCode.DOWN -> {
                    resultsList.requestFocus()
                    if (resultsList.selectionModel.isEmpty) resultsList.selectionModel.selectFirst()
                }
                KeyCode.ENTER -> {
                    (resultsList.selectionModel.selectedItem ?: resultsList.items.firstOrNull())?.let(::runAndClose)
                }
                else -> {}
            }
        }
        resultsList.setOnKeyPressed { event ->
            when (event.code) {
                KeyCode.ESCAPE -> onClose()
                KeyCode.ENTER -> resultsList.selectionModel.selectedItem?.let(::runAndClose)
                else -> {}
            }
        }
        resultsList.setOnMouseClicked {
            resultsList.selectionModel.selectedItem?.let(::runAndClose)
        }
        setOnMouseClicked { event -> if (event.target === this) onClose() }
    }

    private fun runAndClose(action: CommandPaletteAction) {
        onClose()
        action.action()
    }

    /** Focuses the search field once the overlay is attached to a scene. */
    fun focusSearchField() {
        Platform.runLater { searchField.requestFocus() }
    }
}
