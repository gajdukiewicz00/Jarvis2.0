package org.jarvis.desktop.service

import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.awt.Toolkit
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI

/**
 * Service for local system control commands.
 * Handles media control (playerctl), volume (pactl), hotkeys, apps, windows, etc.
 */
class SystemControlService {
    private val logger = LoggerFactory.getLogger(SystemControlService::class.java)
    private data class CommandResult(
        val command: List<String>,
        val exitCode: Int,
        val output: String
    )

    // ==================== Volume Control ====================

    /**
     * Change volume by delta percent.
     * @param delta Amount to change
     * @param sign "+" or "-"
     */
    fun changeVolume(delta: Int, sign: String): Result<Unit> {
        return try {
            val actualSign = if (sign == "+") "+" else "-"
            val backend = selectAudioBackend()
            when (backend) {
                "wpctl" -> executeCheckedCommand("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "$delta%$actualSign")
                "pactl" -> executeCheckedCommand("pactl", "set-sink-volume", "@DEFAULT_SINK@", "$actualSign$delta%")
                "amixer" -> executeCheckedCommand("amixer", "set", "Master", "$delta%$actualSign")
                else -> error("Unsupported audio backend: $backend")
            }
            logger.info("🔊 Volume changed by {}{} via {}", actualSign, "$delta%", backend)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not change volume: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Set volume to a specific percentage.
     */
    fun setVolume(percent: Int): Result<Unit> {
        val level = percent.coerceIn(0, 100)
        return try {
            val backend = selectAudioBackend()
            when (backend) {
                "wpctl" -> executeCheckedCommand("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "$level%")
                "pactl" -> executeCheckedCommand("pactl", "set-sink-volume", "@DEFAULT_SINK@", "$level%")
                "amixer" -> executeCheckedCommand("amixer", "set", "Master", "$level%")
                else -> error("Unsupported audio backend: $backend")
            }
            logger.info("🔊 Volume set to {} via {}", "$level%", backend)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not set volume: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Mute system audio.
     */
    fun mute(): Result<Unit> {
        return try {
            val backend = selectAudioBackend()
            when (backend) {
                "wpctl" -> executeCheckedCommand("wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "1")
                "pactl" -> executeCheckedCommand("pactl", "set-sink-mute", "@DEFAULT_SINK@", "1")
                "amixer" -> executeCheckedCommand("amixer", "set", "Master", "mute")
                else -> error("Unsupported audio backend: $backend")
            }
            logger.info("🔇 System muted via {}", backend)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not mute: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Unmute system audio.
     */
    fun unmute(): Result<Unit> {
        return try {
            val backend = selectAudioBackend()
            when (backend) {
                "wpctl" -> executeCheckedCommand("wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "0")
                "pactl" -> executeCheckedCommand("pactl", "set-sink-mute", "@DEFAULT_SINK@", "0")
                "amixer" -> executeCheckedCommand("amixer", "set", "Master", "unmute")
                else -> error("Unsupported audio backend: $backend")
            }
            logger.info("🔊 System unmuted via {}", backend)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not unmute: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Toggle mute state.
     */
    fun toggleMute(): Result<Unit> {
        return try {
            val backend = selectAudioBackend()
            when (backend) {
                "wpctl" -> executeCheckedCommand("wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle")
                "pactl" -> executeCheckedCommand("pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle")
                "amixer" -> executeCheckedCommand("amixer", "set", "Master", "toggle")
                else -> error("Unsupported audio backend: $backend")
            }
            logger.info("🔊 Mute toggled via {}", backend)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not toggle mute: ${e.message}")
            Result.failure(e)
        }
    }

    // ==================== Media Control ====================

    /**
     * Control media playback using playerctl.
     * @param action One of: PLAY, PAUSE, PLAY_PAUSE, NEXT, PREV/PREVIOUS, STOP
     */
    fun mediaControl(action: String): Result<Unit> {
        val playerctlAction = when (action.uppercase()) {
            "PLAY" -> "play"
            "PAUSE" -> "pause"
            "PLAY_PAUSE", "TOGGLE" -> "play-pause"
            "NEXT" -> "next"
            "PREV", "PREVIOUS" -> "previous"
            "STOP" -> "stop"
            else -> action.lowercase()
        }
        
        // Target resolution: prefer a real MPRIS player; fall back to a browser hotkey only when
        // the active window is actually a browser; otherwise report truthfully that nothing is
        // controllable. Coded prefixes (before ':') become the failureCode the voice layer maps.
        val playerctlInstalled = isCommandAvailable("playerctl")
        val activePlayers = if (playerctlInstalled) {
            runCatching { executeProcess("playerctl", "-l").output.trim() }.getOrDefault("")
        } else {
            ""
        }
        val hasPlayer = playerctlInstalled
            && activePlayers.isNotBlank()
            && !activePlayers.contains("No players found", ignoreCase = true)

        if (hasPlayer) {
            return try {
                // playerctl exiting 0 means the command was ACCEPTED by the active player — that is
                // a real success and must never be downgraded to a failure by a status re-read that
                // may lag or list several players. We verify status only for diagnostics.
                executeCheckedCommand("playerctl", playerctlAction)
                val expected = when (action.uppercase()) {
                    "PAUSE" -> "Paused"
                    "PLAY" -> "Playing"
                    else -> null
                }
                val confirmed = if (expected != null) {
                    playerctlStatus()?.equals(expected, ignoreCase = true) == true
                } else {
                    true
                }
                logger.info(
                    "🎵 media action={} selectedExecutor=playerctl playerctlExit=0 stateConfirmed={} finalStatus=SUCCESS (players={})",
                    action, confirmed, activePlayers
                )
                Result.success(Unit)
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val coded = when {
                    msg.contains("timed out", ignoreCase = true) -> "PLAYERCTL_TIMEOUT: $msg"
                    msg.contains("permission", ignoreCase = true) || msg.contains("denied", ignoreCase = true) -> "PERMISSION_DENIED: $msg"
                    msg.contains("No players found", ignoreCase = true) -> "NO_ACTIVE_PLAYER: $msg"
                    else -> "MEDIA_CONTROL_FAILED: $msg"
                }
                logger.error("🎵 media action={} selectedExecutor=playerctl finalStatus=FAILED reason={}", action, coded)
                Result.failure(IllegalStateException(coded, e))
            }
        }

        // No MPRIS player — try a best-effort browser media hotkey if a browser window is active.
        val browserResult = tryBrowserMediaFallback(action)
        if (browserResult != null) {
            return browserResult
        }

        val reason = if (!playerctlInstalled) {
            "PLAYERCTL_NOT_INSTALLED: playerctl is not installed and no browser media window is active"
        } else {
            "NO_ACTIVE_PLAYER: no active media player and no controllable browser window"
        }
        logger.info("🎵 Media control '$playerctlAction': $reason")
        return Result.failure(IllegalStateException(reason))
    }

    /**
     * Best-effort browser media control when no MPRIS player exists. Returns null when the active
     * window is NOT a browser (so the caller reports NO_ACTIVE_PLAYER truthfully). When it IS a
     * browser we inject a media hotkey — success here means "the key was sent to the focused
     * window", not a verified state change.
     */
    private fun tryBrowserMediaFallback(action: String): Result<Unit>? {
        val title = activeWindowTitle().lowercase()
        if (title.isBlank()) return null
        val browserMarkers = listOf("firefox", "mozilla", "chrome", "chromium", "yandex", "opera", "brave", "edge", "youtube")
        if (browserMarkers.none { title.contains(it) }) return null
        val isYouTube = title.contains("youtube")
        val keys = when (action.uppercase()) {
            "PLAY", "PAUSE", "PLAY_PAUSE", "TOGGLE" -> "XF86AudioPlay"
            "STOP" -> "XF86AudioStop"
            "NEXT" -> if (isYouTube) "shift+n"
                else return Result.failure(IllegalStateException("BROWSER_NEXT_UNSUPPORTED: browser next-track is only supported on YouTube"))
            "PREV", "PREVIOUS" -> if (isYouTube) "shift+p"
                else return Result.failure(IllegalStateException("BROWSER_PREV_UNSUPPORTED: browser previous is only supported on YouTube"))
            else -> return null
        }
        return try {
            executeCheckedCommand("xdotool", "key", keys)
            logger.info("🎵 Media '$action' sent to active browser via hotkey '$keys' (window='$title')")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("BROWSER_HOTKEY_FAILED: ${e.message}", e))
        }
    }

    /** Active X window title via xdotool; empty string if xdotool/window is unavailable. */
    private fun activeWindowTitle(): String =
        runCatching { executeProcess("xdotool", "getactivewindow", "getwindowname").output.trim() }
            .getOrDefault("")

    /**
     * Pause media playback using playerctl.
     */
    fun pauseMedia(): Result<Unit> {
        return mediaControl("PAUSE")
    }

    /**
     * Resume media playback using playerctl.
     */
    fun resumeMedia(): Result<Unit> {
        return mediaControl("PLAY")
    }

    // ==================== App Control ====================

    /**
     * Open an application by name.
     */
    fun openApp(appName: String): Result<Unit> {
        val commands = when (appName.lowercase()) {
            "browser", "firefox" -> listOf(listOf("firefox"))
            "chrome", "google-chrome" -> listOf(listOf("google-chrome"), listOf("chromium"))
            "terminal", "konsole" -> listOf(listOf("konsole"), listOf("gnome-terminal"), listOf("x-terminal-emulator"))
            "file-manager", "files", "file manager", "folder", "папка", "проводник", "dolphin", "nautilus" ->
                listOf(listOf("nautilus"), listOf("dolphin"), listOf("xdg-open", System.getProperty("user.home") ?: "."))
            "vscode", "vs code", "code", "visual studio code" -> listOf(listOf("code"), listOf("codium"))
            "spotify" -> listOf(listOf("spotify"), listOf("flatpak", "run", "com.spotify.Client"))
            "telegram", "telegram-desktop" -> listOf(
                listOf("telegram-desktop"),
                listOf("telegram"),
                listOf("flatpak", "run", "org.telegram.desktop"),
            )
            "vlc" -> listOf(listOf("vlc"))
            "notepad", "kate", "text-editor" -> listOf(listOf("kate"), listOf("gedit"))
            "settings" -> listOf(listOf("gnome-control-center"), listOf("systemsettings"), listOf("kcmshell6", "systemsettings"))
            else -> listOf(listOf(appName))
        }

        return try {
            startFirstAvailable(commands)
            logger.info("🚀 Opened app: {}", appName)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not open app $appName: ${e.message}")
            Result.failure(e)
        }
    }

    fun openUrl(url: String): Result<Unit> {
        // Voice search URLs carry a raw query (spaces/Cyrillic) which URI(String) rejects — encode
        // the search_query value so "найди на ютубе обзор фольксвагена" opens a valid results page.
        val safeUrl = sanitizeUrl(url)
        return try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(safeUrl))
            } else {
                executeCheckedCommand("xdg-open", safeUrl)
            }
            logger.info("🌐 Opened URL: $safeUrl")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not open URL $safeUrl: ${e.message}")
            Result.failure(e)
        }
    }

    /** URL-encode the search_query value (spaces/Cyrillic) so the URL is valid for URI/browse. */
    private fun sanitizeUrl(url: String): String {
        val marker = "search_query="
        val idx = url.indexOf(marker)
        if (idx < 0) {
            return url.replace(" ", "%20")
        }
        val prefix = url.substring(0, idx + marker.length)
        val query = url.substring(idx + marker.length)
        return prefix + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
    }

    // ==================== Hotkey Control ====================

    /**
     * Execute a hotkey combination.
     * Format: "ctrl+shift+n" or "alt+tab" etc.
     */
    fun executeHotkey(keys: String): Result<Unit> {
        return try {
            // Use xdotool to simulate key presses
            executeCheckedCommand("xdotool", "key", keys)
            logger.info("⌨️ Executed hotkey: $keys")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not execute hotkey $keys: ${e.message}")
            Result.failure(e)
        }
    }

    // ==================== Window Control ====================

    /**
     * Perform a window action.
     * @param action One of: MINIMIZE, MAXIMIZE, FULLSCREEN, CLOSE
     * @param target Optional window name/class
     */
    fun windowAction(action: String, target: String? = null): Result<Unit> {
        val xdotoolAction = when (action.uppercase()) {
            "MINIMIZE" -> "windowminimize"
            "MAXIMIZE" -> "windowmaximize"
            "FULLSCREEN" -> "key F11"
            "CLOSE" -> "windowclose"
            "FOCUS" -> "windowfocus"
            else -> return Result.failure(IllegalArgumentException("Unknown window action: $action"))
        }
        
        return try {
            if (target != null) {
                // Find window by name and perform action
                executeCheckedCommand("xdotool", "search", "--name", target, xdotoolAction)
            } else {
                // Perform on active window
                executeCheckedCommand("xdotool", "getactivewindow", xdotoolAction)
            }
            logger.info("🪟 Window action: $action ${target ?: "(active)"}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not perform window action: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Lock the screen.
     */
    fun lockScreen(): Result<Unit> {
        return try {
            // Try common lock commands
            val lockCommands = listOf(
                listOf("loginctl", "lock-session"),
                listOf("xdg-screensaver", "lock"),
                listOf("gnome-screensaver-command", "-l"),
                listOf("xscreensaver-command", "-lock")
            )
            
            var success = false
            for (cmd in lockCommands) {
                try {
                    val process = ProcessBuilder(cmd).start()
                    val exitCode = process.waitFor()
                    if (exitCode == 0) {
                        success = true
                        break
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            
            if (success) {
                logger.info("🔒 Screen locked")
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException("Could not lock screen"))
            }
        } catch (e: Exception) {
            logger.error("Could not lock screen: ${e.message}")
            Result.failure(e)
        }
    }

    // ==================== Notifications ====================

    /**
     * Show a desktop notification.
     */
    fun showNotification(title: String, message: String): Result<Unit> {
        return try {
            executeCheckedCommand("notify-send", title, message)
            logger.info("📢 Notification: $title - $message")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not show notification: ${e.message}")
            Result.failure(e)
        }
    }

    // ==================== Scenarios ====================

    /**
     * Execute a named scenario (macro).
     */
    fun executeScenario(scenario: String): Result<Unit> {
        return when (scenario.lowercase()) {
            "work", "work_mode" -> {
                // Open work apps, disable notifications
                ensureSuccess(openApp("vscode"))
                ensureSuccess(openApp("firefox"))
                logger.info("📋 Scenario: Work mode activated")
                Result.success(Unit)
            }
            "rest", "rest_mode" -> {
                // Close work apps, play music
                ensureSuccess(mediaControl("PLAY"))
                logger.info("📋 Scenario: Rest mode activated")
                Result.success(Unit)
            }
            "focus", "focus_mode" -> {
                // Enable DND, minimize all
                executeCheckedCommand("xdotool", "key", "Super+d")
                logger.info("📋 Scenario: Focus mode activated")
                Result.success(Unit)
            }
            "house_party" -> {
                // Party mode
                ensureSuccess(mediaControl("PLAY"))
                ensureSuccess(setVolume(70))
                logger.info("📋 Scenario: House party mode activated")
                Result.success(Unit)
            }
            "clean_slate" -> {
                // Close all windows
                executeCheckedCommand("xdotool", "key", "Super+d")
                logger.info("📋 Scenario: Clean slate activated")
                Result.success(Unit)
            }
            "cozy_evening" -> {
                ensureSuccess(mediaControl("PLAY"))
                ensureSuccess(setVolume(25))
                ensureSuccess(showNotification("Jarvis", "Cozy evening protocol activated"))
                logger.info("📋 Scenario: Cozy evening activated")
                Result.success(Unit)
            }
            "guests" -> {
                ensureSuccess(mediaControl("PLAY"))
                ensureSuccess(setVolume(45))
                ensureSuccess(showNotification("Jarvis", "Guest protocol activated"))
                logger.info("📋 Scenario: Guests activated")
                Result.success(Unit)
            }
            "holiday" -> {
                ensureSuccess(mediaControl("PLAY"))
                ensureSuccess(setVolume(55))
                ensureSuccess(showNotification("Jarvis", "Holiday protocol activated"))
                logger.info("📋 Scenario: Holiday activated")
                Result.success(Unit)
            }
            "game" -> {
                ensureSuccess(setVolume(60))
                ensureSuccess(showNotification("Jarvis", "Game mode activated"))
                logger.info("📋 Scenario: Game mode activated")
                Result.success(Unit)
            }
            "morning" -> {
                ensureSuccess(openApp("browser"))
                ensureSuccess(setVolume(35))
                ensureSuccess(showNotification("Jarvis", "Morning protocol activated"))
                logger.info("📋 Scenario: Morning protocol activated")
                Result.success(Unit)
            }
            "leaving" -> {
                ensureSuccess(mediaControl("PAUSE"))
                executeCheckedCommand("xdotool", "key", "Super+d")
                ensureSuccess(showNotification("Jarvis", "Leaving protocol activated"))
                logger.info("📋 Scenario: Leaving protocol activated")
                Result.success(Unit)
            }
            "panic" -> {
                ensureSuccess(mute())
                executeCheckedCommand("xdotool", "key", "Super+d")
                ensureSuccess(showNotification("Jarvis", "Panic protocol activated"))
                logger.info("📋 Scenario: Panic protocol activated")
                Result.success(Unit)
            }
            else -> {
                logger.warn("Unknown scenario: $scenario")
                Result.failure(IllegalArgumentException("Unknown scenario: $scenario"))
            }
        }
    }

    fun sleepMode(): Result<Unit> {
        return try {
            runFirstChecked(
                listOf("systemctl", "suspend"),
                listOf("loginctl", "suspend")
            )
            logger.info("💤 Sleep mode requested")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not enter sleep mode: ${e.message}")
            Result.failure(e)
        }
    }

    fun turnMonitorOff(): Result<Unit> {
        return try {
            runFirstChecked(
                listOf("xset", "dpms", "force", "off"),
                listOf("dbus-send", "--session", "--dest=org.gnome.ScreenSaver", "--type=method_call", "/org/gnome/ScreenSaver", "org.gnome.ScreenSaver.SetActive", "boolean:true")
            )
            logger.info("🖥️ Monitor off requested")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not turn monitor off: ${e.message}")
            Result.failure(e)
        }
    }

    // ==================== Misc ====================

    /**
     * Play a system beep.
     */
    fun beep(): Result<Unit> {
        return try {
            Toolkit.getDefaultToolkit().beep()
            logger.info("🔔 Beep")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not beep: ${e.message}")
            Result.failure(e)
        }
    }

    // ==================== Utilities ====================

    /**
     * Execute a system command.
     * @return command output
     */
    private fun executeCommand(vararg command: String): String {
        return executeProcess(*command).output
    }

    private fun executeCheckedCommand(vararg command: String): String {
        val result = executeProcess(*command)
        if (result.exitCode != 0) {
            throw IllegalStateException(
                "Command '${result.command.joinToString(" ")}' failed with code ${result.exitCode}: ${result.output}"
            )
        }
        return result.output
    }

    private fun executeProcess(vararg command: String): CommandResult {
        logger.info("▶️ Running system command: {}", command.joinToString(" "))
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.forEachLine { output.appendLine(it) }
        }

        val finished = process.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)
        val exitCode: Int
        val trimmed: String
        if (finished) {
            exitCode = process.exitValue()
            trimmed = output.toString().trim()
        } else {
            process.destroyForcibly()
            exitCode = 124
            trimmed = "command timed out"
        }
        if (exitCode == 0) {
            logger.info("✅ Command succeeded: command='{}', output='{}'", command.joinToString(" "), trimmed)
        } else {
            logger.warn("❌ Command failed: command='{}', exitCode={}, output='{}'", command.joinToString(" "), exitCode, trimmed)
        }
        return CommandResult(command.toList(), exitCode, trimmed)
    }

    private fun runFirstChecked(vararg commands: List<String>) {
        var lastError: Exception? = null
        for (command in commands) {
            try {
                executeCheckedCommand(*command.toTypedArray())
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No executable command succeeded")
    }

    private fun startFirstAvailable(commands: List<List<String>>) {
        var lastError: Exception? = null
        for (command in commands) {
            try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No application launcher succeeded")
    }

    /**
     * Check which utilities are available on the system.
     */
    fun checkDependencies(): Map<String, Boolean> {
        return mapOf(
            "wpctl" to isCommandAvailable("wpctl"),
            "pactl" to isCommandAvailable("pactl"),
            "amixer" to isCommandAvailable("amixer"),
            "playerctl" to isCommandAvailable("playerctl"),
            "xdotool" to isCommandAvailable("xdotool"),
            "notify-send" to isCommandAvailable("notify-send")
        )
    }

    private fun selectAudioBackend(): String {
        return when {
            isCommandAvailable("wpctl") -> "wpctl"
            isCommandAvailable("pactl") -> "pactl"
            isCommandAvailable("amixer") -> "amixer"
            else -> throw IllegalStateException("No audio backend available (expected one of: wpctl, pactl, amixer)")
        }
    }

    private fun ensureSuccess(result: Result<Unit>) {
        result.getOrThrow()
    }

    private fun isCommandAvailable(command: String): Boolean {
        return try {
            val output = executeCommand("which", command)
            output.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SystemControlService::class.java)
        
        // Tracks whether WE paused a genuinely-playing player, so the paired
        // resume only restarts media that was actually playing before the wake
        // word. Without this guard, resume (playerctl play) force-starts a player
        // even when nothing was playing — turning music ON after every voice command.
        @Volatile
        private var pausedByUs = false

        /**
         * Runs a playerctl command with a hard 3s timeout so a hung playerctl (D-Bus/player
         * stall) can NEVER block the caller forever. This matters because these run on
         * VoiceSession's single scheduler thread — an untimed hang there froze recordingStartFuture
         * for all subsequent commands (the "stops recording after N commands" bug).
         */
        private fun runPlayerctlBounded(vararg args: String): String {
            return try {
                val proc = ProcessBuilder(listOf("playerctl", *args))
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
                if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    logger.warn("playerctl {} timed out (3s)", args.joinToString(" "))
                    return ""
                }
                out
            } catch (e: Exception) {
                ""
            }
        }

        private fun playerctlStatus(): String = runPlayerctlBounded("status")

        /**
         * Clears the paused-by-us flag so the paired [resumeMediaStatic] no-ops. Used when the
         * user's OWN command was an explicit media pause/stop, so the session does NOT auto-resume
         * media ~1.5s later and undo the pause the user just asked for.
         */
        @JvmStatic
        fun clearPausedByUs() {
            pausedByUs = false
        }

        /**
         * Pause media playback - static method for quick access.
         * Only pauses (and remembers it) when a player is actually Playing, so the
         * paired [resumeMediaStatic] never force-starts media that was not playing.
         */
        @JvmStatic
        fun pauseMediaStatic() {
            pausedByUs = false
            if (!playerctlStatus().equals("Playing", ignoreCase = true)) {
                return
            }
            runPlayerctlBounded("pause")
            pausedByUs = true
        }

        /**
         * Resume media playback - static method for quick access.
         * No-op unless [pauseMediaStatic] actually paused a genuinely-playing player.
         */
        @JvmStatic
        fun resumeMediaStatic() {
            if (!pausedByUs) {
                return
            }
            pausedByUs = false
            runPlayerctlBounded("play")
            logger.info("▶️ Media resumed via playerctl")
        }
    }
}
