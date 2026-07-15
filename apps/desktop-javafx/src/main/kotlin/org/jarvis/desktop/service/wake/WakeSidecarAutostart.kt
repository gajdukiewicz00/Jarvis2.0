package org.jarvis.desktop.service.wake

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Convenience factory for the production `autostart` seam the sidecar providers
 * accept. It spawns the local openWakeWord sidecar launcher
 * (`scripts/run-wakeword-openwakeword.sh`) DETACHED and returns whether the
 * process was launched — the provider then polls health for it to come up.
 *
 * Kept out of the providers themselves (which only take an injected
 * `() -> Boolean`) so unit tests never touch a real process. Never throws.
 */
object WakeSidecarAutostart {

    private val logger = LoggerFactory.getLogger(WakeSidecarAutostart::class.java)

    const val DEFAULT_SCRIPT = "scripts/run-wakeword-openwakeword.sh"

    /**
     * Build an autostart lambda that launches [scriptPath] (resolved against
     * [projectDir]). Returns false without spawning when the script is absent.
     */
    fun of(
        scriptPath: String = DEFAULT_SCRIPT,
        projectDir: File = File(System.getProperty("user.dir"))
    ): () -> Boolean = {
        try {
            val script = resolveScript(scriptPath, projectDir)
            if (script == null || !script.canExecute()) {
                logger.warn("wake.autostart: script not found/executable at {}", scriptPath)
                false
            } else {
                ProcessBuilder("bash", script.absolutePath)
                    .directory(projectDir)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                logger.info("wake.autostart: launched {}", script.absolutePath)
                true
            }
        } catch (e: Exception) {
            logger.warn("wake.autostart: failed to launch {}: {}", scriptPath, e.message)
            false
        }
    }

    private fun resolveScript(scriptPath: String, projectDir: File): File? {
        val direct = File(scriptPath)
        if (direct.isAbsolute) return direct.takeIf { it.exists() }
        val underProject = File(projectDir, scriptPath)
        if (underProject.exists()) return underProject
        // Walk up a few levels — the app may run from a module subdirectory.
        var dir: File? = projectDir
        repeat(5) {
            dir = dir?.parentFile
            val candidate = dir?.let { File(it, scriptPath) }
            if (candidate != null && candidate.exists()) return candidate
        }
        return null
    }
}
