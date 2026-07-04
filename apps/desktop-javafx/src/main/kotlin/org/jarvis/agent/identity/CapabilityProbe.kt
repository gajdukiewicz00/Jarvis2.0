package org.jarvis.agent.identity

import org.jarvis.commands.agent.AgentCapability
import org.slf4j.LoggerFactory
import java.util.EnumSet

/**
 * Phase 6 — best-effort detection of which agent capabilities are
 * available on this host.
 *
 * <p>Heuristics chosen over heavy probes: presence of the audio mixer
 * implies microphone, presence of {@code java.awt.Robot} implies
 * keyboard/mouse automation, presence of OpenCV implies webcam, etc.
 * Each probe is wrapped so a missing dependency degrades the capability
 * set rather than failing the agent boot.</p>
 */
class CapabilityProbe {

    private val log = LoggerFactory.getLogger(CapabilityProbe::class.java)

    fun detect(): EnumSet<AgentCapability> {
        val caps = EnumSet.noneOf(AgentCapability::class.java)

        if (probeAudioInput()) caps.add(AgentCapability.VOICE_CAPTURE)
        if (probeAudioOutput()) caps.add(AgentCapability.TTS_PLAYBACK)
        if (probeWakeWord()) caps.add(AgentCapability.WAKE_WORD)
        if (probeScreen()) caps.add(AgentCapability.SCREEN_CAPTURE)
        if (probeWebcam()) caps.add(AgentCapability.WEBCAM)
        if (probeRobot()) {
            caps.add(AgentCapability.KEYBOARD_AUTOMATION)
            caps.add(AgentCapability.MOUSE_AUTOMATION)
        }
        if (probeFileSystem()) caps.add(AgentCapability.FILE_SYSTEM)
        if (probeBrowserCli()) caps.add(AgentCapability.BROWSER_CONTROL)
        if (probeIdeCli()) caps.add(AgentCapability.IDE_CONTROL)
        if (probePcPower()) caps.add(AgentCapability.PC_POWER)

        log.info("detected agent capabilities: {}", caps)
        return caps
    }

    private fun probeAudioInput(): Boolean = runCatching {
        val mixers = javax.sound.sampled.AudioSystem.getMixerInfo()
        mixers.any { javax.sound.sampled.AudioSystem.getMixer(it).targetLineInfo.isNotEmpty() }
    }.getOrDefault(false)

    private fun probeAudioOutput(): Boolean = runCatching {
        val mixers = javax.sound.sampled.AudioSystem.getMixerInfo()
        mixers.any { javax.sound.sampled.AudioSystem.getMixer(it).sourceLineInfo.isNotEmpty() }
    }.getOrDefault(false)

    private fun probeWakeWord(): Boolean = runCatching {
        Class.forName("ai.picovoice.porcupine.Porcupine"); true
    }.getOrDefault(false)

    private fun probeScreen(): Boolean = runCatching {
        // AWT is enough for screen capture via Robot.createScreenCapture.
        !java.awt.GraphicsEnvironment.isHeadless()
    }.getOrDefault(false)

    private fun probeWebcam(): Boolean = runCatching {
        Class.forName("org.opencv.videoio.VideoCapture"); true
    }.getOrDefault(false)

    private fun probeRobot(): Boolean = runCatching {
        if (java.awt.GraphicsEnvironment.isHeadless()) false
        else { java.awt.Robot(); true }
    }.getOrDefault(false)

    private fun probeFileSystem(): Boolean = runCatching {
        val home = java.nio.file.Path.of(System.getProperty("user.home"))
        java.nio.file.Files.isWritable(home)
    }.getOrDefault(false)

    private fun probeBrowserCli(): Boolean = whichAny("xdg-open", "firefox", "chromium", "google-chrome")
    private fun probeIdeCli(): Boolean = whichAny("code", "idea", "idea-community", "subl", "vim", "nvim")
    private fun probePcPower(): Boolean = whichAny("systemctl", "shutdown", "loginctl")

    private fun whichAny(vararg cmds: String): Boolean {
        val pathDirs = (System.getenv("PATH") ?: "").split(java.io.File.pathSeparator)
        return cmds.any { cmd ->
            pathDirs.any { dir ->
                runCatching {
                    val p = java.nio.file.Path.of(dir, cmd)
                    java.nio.file.Files.isExecutable(p)
                }.getOrDefault(false)
            }
        }
    }
}
