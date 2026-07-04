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
        return try {
            val backend = selectAudioBackend()
            when (backend) {
                "wpctl" -> executeCheckedCommand("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "$percent%")
                "pactl" -> executeCheckedCommand("pactl", "set-sink-volume", "@DEFAULT_SINK@", "$percent%")
                "amixer" -> executeCheckedCommand("amixer", "set", "Master", "$percent%")
                else -> error("Unsupported audio backend: $backend")
            }
            logger.info("🔊 Volume set to {} via {}", "$percent%", backend)
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
        
        return try {
            executeCheckedCommand("playerctl", playerctlAction)
            logger.info("🎵 Media $playerctlAction via playerctl")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not execute media control: ${e.message}")
            Result.failure(e)
        }
    }

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
            "file-manager", "dolphin", "nautilus" -> listOf(listOf("dolphin"), listOf("nautilus"))
            "vscode", "code" -> listOf(listOf("code"))
            "spotify" -> listOf(listOf("spotify"))
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
        return try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                executeCheckedCommand("xdg-open", url)
            }
            logger.info("🌐 Opened URL: $url")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not open URL $url: ${e.message}")
            Result.failure(e)
        }
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

        val exitCode = process.waitFor()
        val trimmed = output.toString().trim()
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

        private fun playerctlStatus(): String =
            try {
                val proc = ProcessBuilder("playerctl", "status")
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
                proc.waitFor()
                out
            } catch (e: Exception) {
                ""
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
            try {
                ProcessBuilder("playerctl", "pause")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                pausedByUs = true
            } catch (e: Exception) {
                // Ignore - media control is optional
            }
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
            try {
                ProcessBuilder("playerctl", "play")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                logger.info("▶️ Media resumed via playerctl")
            } catch (e: Exception) {
                // Ignore - media control is optional
            }
        }
    }
}
