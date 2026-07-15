package org.jarvis.agent.command

import org.jarvis.agent.command.DefaultDesktopActions.DesktopBrowser
import org.jarvis.agent.command.DefaultDesktopActions.ProcessRunner
import org.jarvis.agent.command.DefaultDesktopActions.RunResult
import org.jarvis.agent.command.DefaultDesktopActions.SpawnResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path

/**
 * Supplements [DefaultDesktopActionsTest] with the input-guard early returns
 * and success/fallback branches that the primary suite does not exercise:
 * blank/empty argument rejection, the xdg-open success path, and urgency
 * normalization. All OS seams are injected; nothing real is spawned.
 */
class DefaultDesktopActionsGuardsTest {

    private class StubRunner(
        private val perCommand: Map<String, RunResult> = emptyMap(),
        private val default: RunResult = RunResult(0, "", "")
    ) : ProcessRunner {
        val runs = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeoutSec: Long): RunResult {
            runs += command
            return perCommand[command.firstOrNull()] ?: default
        }
        override fun spawn(command: List<String>): SpawnResult = SpawnResult(true, 1L)
    }

    private class StubBrowser(private val throwing: Boolean = false) : DesktopBrowser {
        override fun browse(uri: URI) {
            if (throwing) throw RuntimeException("no display")
        }
    }

    private fun actions(notes: Path, runner: ProcessRunner = StubRunner(), browser: DesktopBrowser = StubBrowser()) =
        DefaultDesktopActions(notesRoot = notes, processRunner = runner, desktopBrowser = browser)

    @Test
    fun `openApp rejects empty app name`(@TempDir notes: Path) {
        val res = actions(notes).openApp("   ")
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("'app' is required"))
    }

    @Test
    fun `focusWindow fails when both wmctrl and xdotool fail`(@TempDir notes: Path) {
        val runner = StubRunner(perCommand = mapOf(
            "wmctrl" to RunResult(1, "", ""),
            "xdotool" to RunResult(1, "", "")
        ))
        val res = actions(notes, runner).focusWindow("Nothing")
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("no matching window"))
        assertTrue(res.errorReason!!.contains("wmctrl=1"))
        assertTrue(res.errorReason!!.contains("xdotool=1"))
    }

    @Test
    fun `typeText rejects empty text`(@TempDir notes: Path) {
        val res = actions(notes).typeText("")
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("'text' is required"))
    }

    @Test
    fun `openUrl rejects an unparseable URI`(@TempDir notes: Path) {
        val res = actions(notes).openUrl("http://exa mple.com/space")
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("invalid URI"))
    }

    @Test
    fun `openUrl succeeds directly via xdg-open`(@TempDir notes: Path) {
        val runner = StubRunner(perCommand = mapOf("xdg-open" to RunResult(0, "", "")))
        val res = actions(notes, runner).openUrl("https://example.com")
        assertTrue(res.ok)
        assertEquals("xdg-open", res.output["tool"])
        assertEquals("https://example.com", res.output["url"])
    }

    @Test
    fun `openUrl fails when xdg-open and the AWT browser both fail`(@TempDir notes: Path) {
        val runner = StubRunner(perCommand = mapOf("xdg-open" to RunResult(127, "", "missing")))
        val res = actions(notes, runner, browser = StubBrowser(throwing = true)).openUrl("mailto:a@b.com")
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("AWT fallback failed"))
    }

    @Test
    fun `createLocalNote rejects a blank title`(@TempDir notes: Path) {
        val res = actions(notes).createLocalNote("   ", "body")
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("'title' is required"))
    }

    @Test
    fun `showNotification rejects a blank summary`(@TempDir notes: Path) {
        val res = actions(notes).showNotification("  ", "body")
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("'summary' is required"))
    }

    @Test
    fun `showNotification succeeds and normalizes a critical urgency`(@TempDir notes: Path) {
        val runner = StubRunner(perCommand = mapOf("notify-send" to RunResult(0, "", "")))
        val res = actions(notes, runner).showNotification("Alert", "body", urgency = "CRITICAL")
        assertTrue(res.ok)
        assertEquals("notify-send", res.output["tool"])
        assertTrue(runner.runs.single().contains("--urgency=critical"))
    }

    @Test
    fun `showNotification normalizes a low urgency`(@TempDir notes: Path) {
        val runner = StubRunner(perCommand = mapOf("notify-send" to RunResult(0, "", "")))
        actions(notes, runner).showNotification("Alert", "body", urgency = "low")
        assertTrue(runner.runs.single().contains("--urgency=low"))
    }

    @Test
    fun `showNotification maps an unrecognized urgency to normal`(@TempDir notes: Path) {
        val runner = StubRunner(perCommand = mapOf("notify-send" to RunResult(0, "", "")))
        actions(notes, runner).showNotification("Alert", "body", urgency = "bogus")
        assertTrue(runner.runs.single().contains("--urgency=normal"))
    }
}
