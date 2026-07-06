package org.jarvis.agent.command

import org.jarvis.agent.command.DesktopActions.ActionResult
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.awt.Robot
import java.awt.event.KeyEvent
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Phase 6 — Linux-first desktop actions backed by external utilities
 * ({@code xdg-open}, {@code wmctrl}, {@code xdotool}, {@code notify-send}) and
 * AWT fallbacks. Whenever a binary is unavailable the implementation falls
 * back to the JDK or returns a structured failure rather than throwing.
 *
 * <p>OPEN_APP enforces an allowlist (env
 * {@code JARVIS_AGENT_OPEN_APP_ALLOWLIST}, comma-separated) to keep "real
 * local action" from devolving into arbitrary shell exec. The allowlist
 * defaults to a small set of common dev/launcher apps.</p>
 */
class DefaultDesktopActions(
    private val notesRoot: Path = defaultNotesRoot(),
    private val appAllowlist: Set<String> = defaultAppAllowlist(),
    private val processRunner: ProcessRunner = SystemProcessRunner(),
    private val robotProvider: () -> Robot = { Robot() },
    private val desktopBrowser: DesktopBrowser = AwtDesktopBrowser()
) : DesktopActions {

    private val log = LoggerFactory.getLogger(DefaultDesktopActions::class.java)

    override fun openApp(app: String, args: List<String>): ActionResult {
        val key = app.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) {
            return ActionResult.fail("openApp: 'app' is required")
        }
        if (appAllowlist.isNotEmpty() && key !in appAllowlist) {
            return ActionResult.fail(
                "openApp: '$app' not in allowlist (set JARVIS_AGENT_OPEN_APP_ALLOWLIST)"
            )
        }
        val cmd = listOf(app) + args
        val outcome = processRunner.spawn(cmd)
        return if (outcome.spawned) {
            ActionResult.ok(mapOf("pid" to outcome.pid, "command" to cmd.joinToString(" ")))
        } else {
            ActionResult.fail("openApp: failed to spawn $cmd: ${outcome.error}")
        }
    }

    override fun focusWindow(titleSubstring: String): ActionResult {
        if (titleSubstring.isBlank()) {
            return ActionResult.fail("focusWindow: 'title' is required")
        }
        // wmctrl -a does a substring match by title.
        val outcome = processRunner.run(listOf("wmctrl", "-a", titleSubstring), timeoutSec = 3)
        if (outcome.exitCode == 0) {
            return ActionResult.ok(mapOf("title" to titleSubstring))
        }
        // Fallback: try xdotool.
        val xd = processRunner.run(
            listOf("xdotool", "search", "--name", titleSubstring, "windowactivate"),
            timeoutSec = 3
        )
        if (xd.exitCode == 0) {
            return ActionResult.ok(mapOf("title" to titleSubstring, "tool" to "xdotool"))
        }
        return ActionResult.fail(
            "focusWindow: no matching window for '$titleSubstring' (wmctrl=${outcome.exitCode}, xdotool=${xd.exitCode})"
        )
    }

    override fun typeText(text: String, perCharDelayMs: Long): ActionResult {
        if (text.isEmpty()) {
            return ActionResult.fail("typeText: 'text' is required")
        }
        // Prefer xdotool when present — it handles Unicode much better than AWT's Robot.
        val xd = processRunner.run(
            buildList {
                add("xdotool")
                add("type")
                if (perCharDelayMs > 0) {
                    add("--delay"); add(perCharDelayMs.toString())
                }
                add("--")
                add(text)
            },
            timeoutSec = 10
        )
        if (xd.exitCode == 0) {
            return ActionResult.ok(mapOf("chars" to text.length, "tool" to "xdotool"))
        }
        return runCatching {
            val robot = robotProvider()
            for (ch in text) {
                typeChar(robot, ch)
                if (perCharDelayMs > 0) Thread.sleep(perCharDelayMs)
            }
            ActionResult.ok(mapOf("chars" to text.length, "tool" to "awt-robot"))
        }.getOrElse {
            log.warn("typeText fallback (AWT) failed: {}", it.message)
            ActionResult.fail("typeText: xdotool exit=${xd.exitCode} and AWT fallback failed: ${it.message}")
        }
    }

    override fun openUrl(url: String): ActionResult {
        val parsed = runCatching { URI.create(url) }.getOrElse {
            return ActionResult.fail("openUrl: invalid URI: ${it.message}")
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme !in URL_SAFE_SCHEMES) {
            return ActionResult.fail("openUrl: scheme '$scheme' not allowed (allow: $URL_SAFE_SCHEMES)")
        }
        // xdg-open is more reliable on Linux than AWT, but AWT is the portable fallback.
        val xdg = processRunner.run(listOf("xdg-open", url), timeoutSec = 5)
        if (xdg.exitCode == 0) {
            return ActionResult.ok(mapOf("url" to url, "tool" to "xdg-open"))
        }
        return runCatching {
            desktopBrowser.browse(parsed)
            ActionResult.ok(mapOf("url" to url, "tool" to "awt-desktop"))
        }.getOrElse {
            ActionResult.fail("openUrl: xdg-open exit=${xdg.exitCode} and AWT fallback failed: ${it.message}")
        }
    }

    override fun createLocalNote(title: String, body: String, directory: Path?): ActionResult {
        if (title.isBlank()) {
            return ActionResult.fail("createLocalNote: 'title' is required")
        }
        val target = confineToNotesRoot(directory)
            ?: return ActionResult.fail("createLocalNote: 'directory' escapes the allowed notes root")
        return runCatching {
            Files.createDirectories(target)
            val safeTitle = title.replace(SAFE_FILE_RE, "-").trim('-').take(80).ifEmpty { "note" }
            val filename = "${TIMESTAMP_FMT.format(Instant.now())}-$safeTitle.md"
            val file = target.resolve(filename)
            val payload = "# $title\n\n$body\n"
            Files.write(
                file,
                payload.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            ActionResult.ok(mapOf("path" to file.toString(), "bytes" to payload.length))
        }.getOrElse {
            ActionResult.fail("createLocalNote: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    override fun showNotification(summary: String, body: String, urgency: String): ActionResult {
        if (summary.isBlank()) {
            return ActionResult.fail("showNotification: 'summary' is required")
        }
        val outcome = processRunner.run(
            listOf("notify-send", "--urgency=${normalizeUrgency(urgency)}", summary, body),
            timeoutSec = 3
        )
        return if (outcome.exitCode == 0) {
            ActionResult.ok(mapOf("summary" to summary, "tool" to "notify-send"))
        } else {
            // Notification failure isn't catastrophic — surface it as failed but
            // don't crash the executor.
            ActionResult.fail("showNotification: notify-send exit=${outcome.exitCode}: ${outcome.stderr.trim()}")
        }
    }

    override fun getActiveWindow(): ActionResult {
        val xd = processRunner.run(
            listOf("xdotool", "getactivewindow", "getwindowname"),
            timeoutSec = 3
        )
        if (xd.exitCode == 0) {
            val title = xd.stdout.trim()
            val idOutcome = processRunner.run(listOf("xdotool", "getactivewindow"), timeoutSec = 3)
            return ActionResult.ok(
                mapOf(
                    "title" to title,
                    "windowId" to idOutcome.stdout.trim().ifEmpty { null }
                )
            )
        }
        // Fallback: parse `wmctrl -lp` for the focused window — best-effort.
        val wm = processRunner.run(listOf("wmctrl", "-a", ":ACTIVE:", "-v"), timeoutSec = 3)
        return ActionResult.fail(
            "getActiveWindow: xdotool exit=${xd.exitCode} (stderr='${xd.stderr.trim()}'); wmctrl exit=${wm.exitCode}"
        )
    }

    /**
     * Confines a caller/command-supplied notes [directory] to [notesRoot].
     *
     * `directory` on {@code CREATE_LOCAL_NOTE} can arrive verbatim from a
     * RabbitMQ-delivered command payload (see
     * {@code NativeDesktopCommandExecutor}), so it must never be trusted as
     * a raw filesystem path. This:
     *  - resolves a relative `directory` against [notesRoot] (never the
     *    process's working directory),
     *  - normalizes `.`/`..` segments and rejects anything that lexically
     *    escapes [notesRoot] (covers `../..` traversal and absolute-path
     *    escapes such as `/etc`), and
     *  - canonicalizes the deepest *existing* ancestor of the resolved path
     *    and rejects it if a symlink resolves outside [notesRoot] (covers
     *    symlink escape for directories that already exist on disk).
     *
     * Returns `null` when the directory is not confined to [notesRoot];
     * otherwise returns the normalized absolute target path.
     */
    private fun confineToNotesRoot(directory: Path?): Path? {
        val base = notesRoot.toAbsolutePath().normalize()
        // notesRoot is a trusted, locally-configured path (never attacker
        // controlled) — create it eagerly so it can be canonicalized below,
        // even on a brand-new machine where it doesn't exist yet.
        val realBase = runCatching {
            Files.createDirectories(base)
            base.toRealPath()
        }.getOrElse { return null }

        if (directory == null) return base

        val requested = if (directory.isAbsolute) {
            directory.normalize()
        } else {
            base.resolve(directory).normalize()
        }
        if (!requested.startsWith(base)) {
            return null
        }

        // Walk up to the nearest existing ancestor and canonicalize it so a
        // symlink planted inside notesRoot cannot redirect the write
        // elsewhere on disk.
        var existingAncestor: Path? = requested
        while (existingAncestor != null && !Files.exists(existingAncestor)) {
            existingAncestor = existingAncestor.parent
        }
        val realAncestor = existingAncestor?.let { anc ->
            runCatching { anc.toRealPath() }.getOrElse { return null }
        } ?: realBase
        if (!realAncestor.startsWith(realBase)) {
            return null
        }
        return requested
    }

    private fun typeChar(robot: Robot, ch: Char) {
        val keyCode = KeyEvent.getExtendedKeyCodeForChar(ch.code)
        if (keyCode == KeyEvent.VK_UNDEFINED) {
            throw IllegalArgumentException("AWT cannot type character '$ch'")
        }
        val needsShift = ch.isUpperCase() || ch in SHIFTED
        if (needsShift) robot.keyPress(KeyEvent.VK_SHIFT)
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
        if (needsShift) robot.keyRelease(KeyEvent.VK_SHIFT)
    }

    private fun normalizeUrgency(value: String): String =
        when (value.lowercase(Locale.ROOT)) {
            "low" -> "low"
            "critical" -> "critical"
            else -> "normal"
        }

    /**
     * Pluggable wrapper around {@link ProcessBuilder}. Tests inject a
     * deterministic stub instead of touching the real OS.
     */
    interface ProcessRunner {
        fun run(command: List<String>, timeoutSec: Long): RunResult
        fun spawn(command: List<String>): SpawnResult
    }

    data class RunResult(val exitCode: Int, val stdout: String, val stderr: String)
    data class SpawnResult(val spawned: Boolean, val pid: Long?, val error: String? = null)

    /**
     * Production runner. Reads stdout/stderr fully so the test seam stays
     * symmetrical with mocks.
     */
    class SystemProcessRunner : ProcessRunner {
        private val log = LoggerFactory.getLogger(SystemProcessRunner::class.java)

        override fun run(command: List<String>, timeoutSec: Long): RunResult {
            return try {
                val proc = ProcessBuilder(command).redirectErrorStream(false).start()
                val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    RunResult(-1, "", "timed out after ${timeoutSec}s")
                } else {
                    RunResult(
                        proc.exitValue(),
                        proc.inputStream.readAllBytes().toString(StandardCharsets.UTF_8),
                        proc.errorStream.readAllBytes().toString(StandardCharsets.UTF_8)
                    )
                }
            } catch (ex: Exception) {
                log.debug("process run failed for {}: {}", command, ex.message)
                RunResult(-1, "", ex.message ?: ex.javaClass.simpleName)
            }
        }

        override fun spawn(command: List<String>): SpawnResult {
            return try {
                val proc = ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                SpawnResult(true, proc.pid())
            } catch (ex: Exception) {
                SpawnResult(false, null, ex.message ?: ex.javaClass.simpleName)
            }
        }
    }

    /** AWT's {@link Desktop#browse} — small interface so tests can stub it. */
    interface DesktopBrowser {
        fun browse(uri: URI)
    }

    class AwtDesktopBrowser : DesktopBrowser {
        override fun browse(uri: URI) {
            if (!Desktop.isDesktopSupported()) {
                throw UnsupportedOperationException("java.awt.Desktop not supported on this platform")
            }
            Desktop.getDesktop().browse(uri)
        }
    }

    companion object {
        private val URL_SAFE_SCHEMES = setOf("http", "https", "mailto")
        private val SAFE_FILE_RE = Regex("[^A-Za-z0-9._-]+")
        private val TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneId.systemDefault())
        private val SHIFTED = setOf(
            '!', '@', '#', '$', '%', '^', '&', '*', '(', ')',
            '_', '+', '{', '}', '|', ':', '"', '<', '>', '?', '~'
        )

        fun defaultNotesRoot(): Path =
            Path.of(System.getProperty("user.home"), ".jarvis", "notes")

        fun defaultAppAllowlist(): Set<String> {
            val raw = System.getenv("JARVIS_AGENT_OPEN_APP_ALLOWLIST")
            if (raw.isNullOrBlank()) {
                return setOf(
                    "firefox", "chromium", "google-chrome", "code",
                    "gnome-terminal", "konsole", "xterm", "kitty",
                    "thunderbird", "vlc", "nautilus", "thunar", "obsidian"
                )
            }
            return raw.split(",")
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .toSet()
        }
    }
}
