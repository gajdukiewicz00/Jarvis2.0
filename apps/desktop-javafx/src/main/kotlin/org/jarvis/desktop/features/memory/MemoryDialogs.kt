package org.jarvis.desktop.features.memory

import javafx.geometry.Insets
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.VBox

/**
 * Stateless dialog builders for the Memory panel, split out of [MemoryView] so that file stays
 * focused on wiring/network orchestration. Each function owns its own `Dialog<ButtonType>` and
 * returns the user's choice (or `null` on cancel) — [MemoryView] decides what to do with it
 * (submit an edit, apply a scope change, run a forget call, ...).
 */
object MemoryDialogs {

    /** Edit dialog for [MemoryReadModel.NoteDetail] — returns the new (title, body) if saved. */
    fun showEditDialog(detail: MemoryReadModel.NoteDetail): Pair<String, String>? {
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

        val result = dialog.showAndWait()
        return if (result.orElse(null) == saveButton) titleField.text to bodyArea.text else null
    }

    /** "Why does Jarvis remember this?" — read-only provenance dialog. */
    fun showWhyDialog(title: String, info: MemoryReadModel.WhyInfo) {
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

    /** "Move to a new scope" dialog — returns the chosen scope if the user applied it. */
    fun promptScopeChange(title: String, currentScope: String?): String? {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Change scope"
        dialog.headerText = "Move \"$title\" to a new scope"

        val scopeCombo = ComboBox<String>().apply {
            items.addAll(MemoryReadModel.SCOPES)
            value = currentScope?.takeIf { it in MemoryReadModel.SCOPES } ?: MemoryReadModel.SCOPES.first()
        }
        dialog.dialogPane.content = VBox(10.0).apply {
            padding = Insets(12.0)
            children += Label("Scope")
            children += scopeCombo
        }
        val applyButton = ButtonType("Apply", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.setAll(applyButton, ButtonType.CANCEL)

        val result = dialog.showAndWait()
        return if (result.orElse(null) == applyButton) scopeCombo.value ?: MemoryReadModel.SCOPES.first() else null
    }

    /**
     * HIGH-risk confirmation for "Jarvis, forget this" — backs the single-note, multi-select
     * bulk, and "forget by query" flows alike. Returns the reason the user entered (may be
     * blank) if they confirmed, or `null` if cancelled.
     */
    fun promptForgetReason(target: String): String? {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Forget memory note"
        dialog.headerText = "Forget \"$target\"?"

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
}
