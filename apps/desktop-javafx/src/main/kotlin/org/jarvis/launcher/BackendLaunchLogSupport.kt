package org.jarvis.launcher

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object BackendLaunchLogSupport {
    private val logger = LoggerFactory.getLogger(BackendLaunchLogSupport::class.java)

    fun writeBackendLaunchPreamble(scriptPath: Path, envVars: Map<String, String>) {
        try {
            val lines = buildList {
                add("[${java.time.Instant.now()}] Jarvis backend launch")
                add("Runtime target: ${JarvisPaths.describeRuntimeTarget()}")
                add("Launch script: $scriptPath")
                add("Working directory: ${scriptPath.parent}")
                add(
                    "Launcher flags: ${
                        envVars.entries
                            .sortedBy { it.key }
                            .joinToString(", ") { "${it.key}=${it.value}" }
                    }"
                )
                add("")
            }
            Files.createDirectories(JarvisPaths.backendLaunchLog.parent)
            Files.writeString(
                JarvisPaths.backendLaunchLog,
                lines.joinToString("\n"),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        } catch (e: Exception) {
            logger.warn("Failed to write backend launch preamble", e)
        }
    }

    fun appendBackendLaunchLogLine(line: String) {
        try {
            Files.writeString(
                JarvisPaths.backendLaunchLog,
                "[${java.time.Instant.now()}] $line\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        } catch (e: Exception) {
            logger.warn("Failed to append backend launch log line", e)
        }
    }
}
