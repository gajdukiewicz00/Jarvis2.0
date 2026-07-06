package org.jarvis.agent.command

import org.jarvis.agent.command.DefaultDesktopActions.DesktopBrowser
import org.jarvis.agent.command.DefaultDesktopActions.ProcessRunner
import org.jarvis.agent.command.DefaultDesktopActions.RunResult
import org.jarvis.agent.command.DefaultDesktopActions.SpawnResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class DefaultDesktopActionsTest {

    private class StubRunner : ProcessRunner {
        val runs = mutableListOf<List<String>>()
        val spawns = mutableListOf<List<String>>()
        var nextRunResult: RunResult = RunResult(0, "", "")
        val perCommandRun: MutableMap<String, RunResult> = mutableMapOf()
        var nextSpawnResult: SpawnResult = SpawnResult(true, 4242L)

        override fun run(command: List<String>, timeoutSec: Long): RunResult {
            runs += command
            return perCommandRun[command.firstOrNull()] ?: nextRunResult
        }
        override fun spawn(command: List<String>): SpawnResult {
            spawns += command
            return nextSpawnResult
        }
    }

    private class StubBrowser : DesktopBrowser {
        val opened = mutableListOf<URI>()
        var throwOnNext: Throwable? = null
        override fun browse(uri: URI) {
            throwOnNext?.let { throw it }
            opened += uri
        }
    }

    @Test
    fun `openApp respects allowlist`(@TempDir notes: Path) {
        val runner = StubRunner()
        val actions = DefaultDesktopActions(
            notesRoot = notes,
            appAllowlist = setOf("firefox"),
            processRunner = runner
        )

        val ok = actions.openApp("firefox", listOf("-private"))
        assertTrue(ok.ok)
        assertEquals(listOf(listOf("firefox", "-private")), runner.spawns)
        assertEquals(4242L, ok.output["pid"])

        val denied = actions.openApp("rm", listOf("-rf", "/"))
        assertFalse(denied.ok)
        assertTrue(denied.errorReason!!.contains("not in allowlist"))
        assertEquals(1, runner.spawns.size, "denied app must not have been spawned")
    }

    @Test
    fun `openApp allows anything when allowlist is empty`(@TempDir notes: Path) {
        val runner = StubRunner()
        val actions = DefaultDesktopActions(
            notesRoot = notes,
            appAllowlist = emptySet(),
            processRunner = runner
        )
        val ok = actions.openApp("anything", listOf())
        assertTrue(ok.ok)
    }

    @Test
    fun `openApp surfaces spawn failures`(@TempDir notes: Path) {
        val runner = StubRunner().also { it.nextSpawnResult = SpawnResult(false, null, "ENOENT") }
        val actions = DefaultDesktopActions(
            notesRoot = notes,
            appAllowlist = setOf("firefox"),
            processRunner = runner
        )
        val res = actions.openApp("firefox", emptyList())
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("ENOENT"))
    }

    @Test
    fun `focusWindow falls back to xdotool when wmctrl fails`(@TempDir notes: Path) {
        val runner = StubRunner()
        runner.perCommandRun["wmctrl"] = RunResult(1, "", "no such window")
        runner.perCommandRun["xdotool"] = RunResult(0, "win-1\n", "")
        val res = DefaultDesktopActions(notesRoot = notes, processRunner = runner).focusWindow("Editor")
        assertTrue(res.ok)
        assertEquals("xdotool", res.output["tool"])
        assertEquals(2, runner.runs.size)
    }

    @Test
    fun `focusWindow rejects blank title`(@TempDir notes: Path) {
        val res = DefaultDesktopActions(notesRoot = notes, processRunner = StubRunner())
            .focusWindow("   ")
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("required"))
    }

    @Test
    fun `typeText prefers xdotool when present`(@TempDir notes: Path) {
        val runner = StubRunner()
        runner.perCommandRun["xdotool"] = RunResult(0, "", "")
        val res = DefaultDesktopActions(notesRoot = notes, processRunner = runner).typeText("hi", 5)
        assertTrue(res.ok)
        assertEquals("xdotool", res.output["tool"])
        val invoked = runner.runs.single()
        assertEquals(listOf("xdotool", "type", "--delay", "5", "--", "hi"), invoked)
    }

    @Test
    fun `typeText fails when both xdotool and AWT are unavailable`(@TempDir notes: Path) {
        val runner = StubRunner()
        runner.perCommandRun["xdotool"] = RunResult(127, "", "command not found")
        val actions = DefaultDesktopActions(
            notesRoot = notes,
            processRunner = runner,
            robotProvider = { throw java.awt.AWTException("headless") }
        )
        val res = actions.typeText("anything", 0)
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("AWT fallback failed"))
    }

    @Test
    fun `openUrl rejects schemes outside the safelist`(@TempDir notes: Path) {
        val res = DefaultDesktopActions(notesRoot = notes, processRunner = StubRunner())
            .openUrl("file:///etc/passwd")
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("scheme 'file' not allowed"))
    }

    @Test
    fun `openUrl falls back to AWT when xdg-open is missing`(@TempDir notes: Path) {
        val runner = StubRunner()
        runner.perCommandRun["xdg-open"] = RunResult(127, "", "no xdg-open")
        val browser = StubBrowser()
        val res = DefaultDesktopActions(
            notesRoot = notes,
            processRunner = runner,
            desktopBrowser = browser
        ).openUrl("https://example.com")
        assertTrue(res.ok)
        assertEquals("awt-desktop", res.output["tool"])
        assertEquals(URI.create("https://example.com"), browser.opened.single())
    }

    @Test
    fun `createLocalNote writes a markdown file under the notes root`(@TempDir notes: Path) {
        val res = DefaultDesktopActions(notesRoot = notes, processRunner = StubRunner())
            .createLocalNote("Standup notes!", "- ship it\n- ship more")
        assertTrue(res.ok)
        val path = Path.of(res.output["path"] as String)
        assertTrue(path.startsWith(notes.toAbsolutePath()))
        val contents = Files.readString(path)
        assertTrue(contents.startsWith("# Standup notes!\n"))
        assertTrue(contents.contains("- ship it"))
    }

    @Test
    fun `createLocalNote uses caller-supplied directory when it is inside the notes root`(@TempDir notes: Path) {
        val custom = notes.resolve("projectA").resolve("subdir")
        val res = DefaultDesktopActions(notesRoot = notes, processRunner = StubRunner())
            .createLocalNote("Custom", "body", directory = custom)
        assertTrue(res.ok)
        val path = Path.of(res.output["path"] as String)
        assertTrue(path.startsWith(custom.toAbsolutePath()))
    }

    @Test
    fun `createLocalNote rejects a relative directory that traverses outside the notes root`(@TempDir notes: Path) {
        // A relative "directory" is resolved against notesRoot, never the
        // process cwd — "../../evil" must still be rejected as an escape.
        val escaping = Path.of("..", "..", "evil-sibling")
        val res = DefaultDesktopActions(notesRoot = notes, processRunner = StubRunner())
            .createLocalNote("Evil", "body", directory = escaping)
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("escapes the allowed notes root"))
        assertFalse(
            Files.exists(notes.toAbsolutePath().parent.parent.resolve("evil-sibling")),
            "must not have written outside the notes root"
        )
    }

    @Test
    fun `createLocalNote rejects an absolute directory outside the notes root`(@TempDir notes: Path,
                                                                                @TempDir outside: Path) {
        val res = DefaultDesktopActions(notesRoot = notes, processRunner = StubRunner())
            .createLocalNote("Evil", "body", directory = outside)
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("escapes the allowed notes root"))
        assertTrue(Files.list(outside).use { it.count() } == 0L, "must not have written into the escaped directory")
    }

    @Test
    fun `createLocalNote rejects a symlink inside the notes root that escapes to another directory`(
        @TempDir notes: Path,
        @TempDir outside: Path
    ) {
        val link = notes.resolve("escape-link")
        java.nio.file.Files.createSymbolicLink(link, outside)
        val res = DefaultDesktopActions(notesRoot = notes, processRunner = StubRunner())
            .createLocalNote("Evil", "body", directory = link)
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("escapes the allowed notes root"))
        assertTrue(Files.list(outside).use { it.count() } == 0L, "must not have written through the symlink")
    }

    @Test
    fun `showNotification reports notify-send exit codes`(@TempDir notes: Path) {
        val runner = StubRunner()
        runner.perCommandRun["notify-send"] = RunResult(127, "", "no notify-send")
        val res = DefaultDesktopActions(notesRoot = notes, processRunner = runner)
            .showNotification("hi", "there")
        assertFalse(res.ok)
        assertTrue(res.errorReason!!.contains("notify-send"))
    }

    @Test
    fun `getActiveWindow returns title when xdotool succeeds`(@TempDir notes: Path) {
        val runner = StubRunner()
        runner.perCommandRun["xdotool"] = RunResult(0, "Editor — main.kt\n", "")
        val res = DefaultDesktopActions(notesRoot = notes, processRunner = runner).getActiveWindow()
        assertTrue(res.ok)
        assertEquals("Editor — main.kt", res.output["title"])
        assertNotNull(res.output["windowId"])
    }

    @Test
    fun `getActiveWindow surfaces failure when xdotool is missing`(@TempDir notes: Path) {
        val runner = StubRunner()
        runner.perCommandRun["xdotool"] = RunResult(127, "", "no xdotool")
        val res = DefaultDesktopActions(notesRoot = notes, processRunner = runner).getActiveWindow()
        assertFalse(res.ok)
        assertNull(res.output["title"])
    }
}
