package org.jarvis.desktop.e2e.launcher

import javafx.event.ActionEvent
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.launcher.LogViewer
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Headless UI coverage for the launcher [LogViewer]. `createUI()` builds the
 * header controls, kicks off a background read of the selected log file, and
 * updates the status label on the FX thread. The reads target the real
 * ~/.jarvis/logs log files (present on this runtime); every displayed line is
 * run through [org.jarvis.launcher.SecurityUtils] masking. No control opens a
 * modal dialog, so combo switch / refresh / auto-scroll toggle are safe to
 * drive. Each test calls [LogViewer.stop] to tear the executors down.
 */
class LogViewerE2eTest {

    @Test
    fun `createUI reads the default log file and reports masked status`() {
        var viewer: LogViewer? = null
        val root = E2eFx.onFx { LogViewer().also { viewer = it }.createUI() }
        try {
            assertNotNull(root, "createUI should return a root container")

            E2eFx.waitForFx(description = "launcher.log status") {
                E2eFx.hasText(root, "Viewing launcher.log") && E2eFx.hasText(root, "secrets masked")
            }

            // The refresh button re-reads the same file without error.
            E2eFx.onFx {
                requireNotNull(E2eFx.findAll<Button>(root).firstOrNull { it.text == "Refresh" }) {
                    "Refresh button not found"
                }.fire()
            }
            E2eFx.waitForFx(description = "status after refresh") { E2eFx.hasText(root, "secrets masked") }
        } finally {
            viewer?.stop()
        }
    }

    @Test
    fun `switching the file selector loads the backend launch log`() {
        var viewer: LogViewer? = null
        val root = E2eFx.onFx { LogViewer().also { viewer = it }.createUI() }
        try {
            E2eFx.waitForFx(description = "initial load") { E2eFx.hasText(root, "Viewing launcher.log") }

            E2eFx.onFx {
                @Suppress("UNCHECKED_CAST")
                val combo = requireNotNull(E2eFx.find<ComboBox<*>>(root)) { "file selector not found" }
                        as ComboBox<String>
                combo.value = "backend-launch.log"
                combo.fireEvent(ActionEvent()) // route to the onAction -> switchLogFile handler
            }

            E2eFx.waitForFx(description = "backend log status") {
                E2eFx.hasText(root, "Viewing backend-launch.log") && E2eFx.hasText(root, "current run")
            }
        } finally {
            viewer?.stop()
        }
    }

    @Test
    fun `toggling auto-scroll does not disturb the running viewer`() {
        var viewer: LogViewer? = null
        val root = E2eFx.onFx { LogViewer().also { viewer = it }.createUI() }
        try {
            E2eFx.waitForFx(description = "initial load") { E2eFx.hasText(root, "Viewing launcher.log") }

            E2eFx.onFx {
                val checkBox = requireNotNull(E2eFx.find<CheckBox>(root)) { "auto-scroll checkbox not found" }
                checkBox.isSelected = false
                checkBox.fireEvent(ActionEvent())
            }

            assertTrue(
                E2eFx.onFx { E2eFx.find<CheckBox>(root)?.isSelected == false },
                "auto-scroll checkbox should reflect the toggled-off state"
            )
        } finally {
            viewer?.stop()
        }
    }
}
