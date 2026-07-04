package org.jarvis.agent.command

import java.nio.file.Path

/**
 * Phase 6 — testable seam for OS-level desktop work.
 *
 * <p>The native executor stays platform-agnostic by talking to this interface.
 * The default implementation shells out to {@code xdg-open}, {@code wmctrl},
 * {@code xdotool}, {@code notify-send}, falls back to AWT/JavaFX where the
 * binaries are missing, and is unit-test mocked everywhere else.</p>
 *
 * <p>Every method returns a structured result rather than throwing, so the
 * executor can decide between SUCCESS/FAILED uniformly.</p>
 */
interface DesktopActions {

    /** Result of a sub-action. {@code output} is forwarded to the orchestrator. */
    data class ActionResult(
        val ok: Boolean,
        val output: Map<String, Any?> = emptyMap(),
        val errorReason: String? = null
    ) {
        companion object {
            fun ok(output: Map<String, Any?> = emptyMap()) = ActionResult(true, output)
            fun fail(reason: String, output: Map<String, Any?> = emptyMap()) =
                ActionResult(false, output, reason)
        }
    }

    /** Launch a desktop application. Implementations enforce an allowlist. */
    fun openApp(app: String, args: List<String> = emptyList()): ActionResult

    /** Bring a window with the given (sub)title to the foreground. */
    fun focusWindow(titleSubstring: String): ActionResult

    /** Type the given text into whichever window currently has focus. */
    fun typeText(text: String, perCharDelayMs: Long = 0): ActionResult

    /** Open a URL in the user's default browser. */
    fun openUrl(url: String): ActionResult

    /** Persist a note to the local notes directory. Returns the absolute file path. */
    fun createLocalNote(title: String, body: String, directory: Path? = null): ActionResult

    /** Display a desktop notification (e.g. notify-send / os toast). */
    fun showNotification(summary: String, body: String, urgency: String = "normal"): ActionResult

    /** Look up the focused window's title (and id/pid where available). */
    fun getActiveWindow(): ActionResult
}
