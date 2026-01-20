package org.jarvis.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class LauncherSettings(
    val enableLlm: Boolean = false,
    val enableMemory: Boolean = false,
    val enableGpu: Boolean = true
)

class LauncherConfig(private val configPath: Path) {
    fun load(): LauncherSettings {
        if (!Files.exists(configPath)) {
            return LauncherSettings()
        }
        val props = Properties()
        Files.newInputStream(configPath).use { props.load(it) }
        return LauncherSettings(
            enableLlm = props.getProperty("enableLlm")?.toBoolean() ?: false,
            enableMemory = props.getProperty("enableMemory")?.toBoolean() ?: false,
            enableGpu = props.getProperty("enableGpu")?.toBoolean() ?: true
        )
    }

    fun save(settings: LauncherSettings) {
        val props = Properties()
        props["enableLlm"] = settings.enableLlm.toString()
        props["enableMemory"] = settings.enableMemory.toString()
        props["enableGpu"] = settings.enableGpu.toString()
        Files.createDirectories(configPath.parent)
        Files.newOutputStream(configPath).use { props.store(it, "Jarvis Launcher Settings") }
    }
}
