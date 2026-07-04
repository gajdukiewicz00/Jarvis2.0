package org.jarvis.desktop.features.common

import javafx.scene.control.Label

/**
 * Small shared helpers for shell-native feature panels.
 *
 * These reuse the existing shell CSS tokens (`shell-status-pill`,
 * `shell-status-tone-*`, `shell-section-*`) so newly added panels match the
 * established design language without duplicating tone-class bookkeeping.
 */
object ShellPanelSupport {

    val TONE_CLASSES: Set<String> = setOf(
        "shell-status-tone-muted",
        "shell-status-tone-info",
        "shell-status-tone-success",
        "shell-status-tone-warning",
        "shell-status-tone-error"
    )

    fun statusPill(text: String): Label =
        Label(text).apply {
            styleClass += "shell-status-pill"
            styleClass += "shell-status-tone-muted"
        }

    fun sectionTitle(text: String): Label =
        Label(text).apply { styleClass += "shell-section-title" }

    fun sectionSubtitle(text: String): Label =
        Label(text).apply {
            styleClass += "shell-section-subtitle"
            isWrapText = true
        }

    fun applyTone(label: Label, toneClass: String) {
        label.styleClass.removeIf { it in TONE_CLASSES }
        if (toneClass !in label.styleClass) {
            label.styleClass += toneClass
        }
    }
}
