package org.jarvis.desktop.features.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bare JavaFX [javafx.scene.control.Label] construction (no [Scene]/[Stage]
 * shown, no [javafx.application.Platform.startup]) works off the FX thread
 * for simple property-graph assembly — only actual rendering/layout needs
 * the running toolkit. This is a probe for that assumption as much as a
 * regression test: if headless JavaFX construction stops working in this
 * environment, this file is the first thing that will fail loudly.
 */
class ShellPanelSupportTest {

    @Test
    fun `statusPill applies the pill and muted tone style classes`() {
        val label = ShellPanelSupport.statusPill("Ready")
        assertEquals("Ready", label.text)
        assertTrue(label.styleClass.contains("shell-status-pill"))
        assertTrue(label.styleClass.contains("shell-status-tone-muted"))
    }

    @Test
    fun `sectionTitle applies the section-title style class`() {
        val label = ShellPanelSupport.sectionTitle("Overview")
        assertEquals("Overview", label.text)
        assertTrue(label.styleClass.contains("shell-section-title"))
    }

    @Test
    fun `sectionSubtitle wraps text and applies the subtitle style class`() {
        val label = ShellPanelSupport.sectionSubtitle("Details go here")
        assertEquals("Details go here", label.text)
        assertTrue(label.isWrapText)
        assertTrue(label.styleClass.contains("shell-section-subtitle"))
    }

    @Test
    fun `applyTone swaps the previous tone class for the new one`() {
        val label = ShellPanelSupport.statusPill("Status")
        assertTrue(label.styleClass.contains("shell-status-tone-muted"))

        ShellPanelSupport.applyTone(label, "shell-status-tone-success")

        assertTrue(label.styleClass.contains("shell-status-tone-success"))
        assertFalse(label.styleClass.contains("shell-status-tone-muted"))
    }

    @Test
    fun `applyTone is idempotent when the tone is already applied`() {
        val label = ShellPanelSupport.statusPill("Status")
        ShellPanelSupport.applyTone(label, "shell-status-tone-error")
        ShellPanelSupport.applyTone(label, "shell-status-tone-error")

        assertEquals(1, label.styleClass.count { it == "shell-status-tone-error" })
    }

    @Test
    fun `TONE_CLASSES enumerates every recognized tone`() {
        assertEquals(
            setOf(
                "shell-status-tone-muted",
                "shell-status-tone-info",
                "shell-status-tone-success",
                "shell-status-tone-warning",
                "shell-status-tone-error"
            ),
            ShellPanelSupport.TONE_CLASSES
        )
    }
}
