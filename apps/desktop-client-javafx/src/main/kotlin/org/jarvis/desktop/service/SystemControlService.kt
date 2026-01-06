package org.jarvis.desktop.service

import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.awt.Toolkit
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Service for local system control commands.
 * Handles media control (playerctl), volume (pactl), hotkeys, apps, windows, etc.
 */
class SystemControlService {
    private val logger = LoggerFactory.getLogger(SystemControlService::class.java)

    // ==================== Volume Control ====================

    /**
     * Change volume by delta percent.
     * @param delta Amount to change
     * @param sign "+" or "-"
     */
    fun changeVolume(delta: Int, sign: String): Result<Unit> {
        return try {
            val actualSign = if (sign == "+") "+" else "-"
            executeCommand("pactl", "set-sink-volume", "@DEFAULT_SINK@", "$actualSign$delta%")
            logger.info("🔊 Volume changed by $actualSign$delta% via pactl")
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
            executeCommand("pactl", "set-sink-volume", "@DEFAULT_SINK@", "$percent%")
            logger.info("🔊 Volume set to $percent% via pactl")
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
            executeCommand("pactl", "set-sink-mute", "@DEFAULT_SINK@", "1")
            logger.info("🔇 System muted via pactl")
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
            executeCommand("pactl", "set-sink-mute", "@DEFAULT_SINK@", "0")
            logger.info("🔊 System unmuted via pactl")
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
            executeCommand("pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle")
            logger.info("🔊 Mute toggled via pactl")
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
            executeCommand("playerctl", playerctlAction)
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
        val command = when (appName.lowercase()) {
            "browser", "firefox" -> "firefox"
            "chrome", "google-chrome" -> "google-chrome"
            "terminal", "konsole" -> "konsole"
            "file-manager", "dolphin", "nautilus" -> "dolphin"
            "vscode", "code" -> "code"
            "spotify" -> "spotify"
            "vlc" -> "vlc"
            "notepad", "kate", "text-editor" -> "kate"
            else -> appName
        }
        
        return try {
            ProcessBuilder(command).apply {
                environment()
                inheritIO()
            }.start()
            logger.info("🚀 Opened app: $command")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Could not open app $appName: ${e.message}")
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
            executeCommand("xdotool", "key", keys)
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
                executeCommand("xdotool", "search", "--name", target, xdotoolAction)
            } else {
                // Perform on active window
                executeCommand("xdotool", "getactivewindow", xdotoolAction)
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
            executeCommand("notify-send", title, message)
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
                openApp("vscode")
                openApp("firefox")
                logger.info("📋 Scenario: Work mode activated")
                Result.success(Unit)
            }
            "rest", "rest_mode" -> {
                // Close work apps, play music
                mediaControl("PLAY")
                logger.info("📋 Scenario: Rest mode activated")
                Result.success(Unit)
            }
            "focus", "focus_mode" -> {
                // Enable DND, minimize all
                executeCommand("xdotool", "key", "Super+d")
                logger.info("📋 Scenario: Focus mode activated")
                Result.success(Unit)
            }
            "house_party" -> {
                // Party mode
                mediaControl("PLAY")
                setVolume(70)
                logger.info("📋 Scenario: House party mode activated")
                Result.success(Unit)
            }
            "clean_slate" -> {
                // Close all windows
                executeCommand("xdotool", "key", "Super+d")
                logger.info("📋 Scenario: Clean slate activated")
                Result.success(Unit)
            }
            else -> {
                logger.warn("Unknown scenario: $scenario")
                Result.failure(IllegalArgumentException("Unknown scenario: $scenario"))
            }
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
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        
        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.forEachLine { output.appendLine(it) }
        }
        
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.debug("Command '${command.joinToString(" ")}' exited with code $exitCode: $output")
        }
        
        return output.toString().trim()
    }

    /**
     * Check which utilities are available on the system.
     */
    fun checkDependencies(): Map<String, Boolean> {
        return mapOf(
            "pactl" to isCommandAvailable("pactl"),
            "playerctl" to isCommandAvailable("playerctl"),
            "xdotool" to isCommandAvailable("xdotool"),
            "notify-send" to isCommandAvailable("notify-send")
        )
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
        
        /**
         * Pause media playback - static method for quick access.
         * Used by VoiceSession to pause media on wake word.
         */
        @JvmStatic
        fun pauseMediaStatic() {
            try {
                ProcessBuilder("playerctl", "pause")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            } catch (e: Exception) {
                // Ignore - media control is optional
            }
        }
        
        /**
         * Resume media playback - static method for quick access.
         * Used by VoiceSession to resume media after command is processed.
         */
        @JvmStatic
        fun resumeMediaStatic() {
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
