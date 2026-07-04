package org.jarvis.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Persisted launcher settings.
 *
 * Schema versions:
 *   - missing/1: pre-2026-05 defaults (LLM/Memory off). Auto-migrated to v2.
 *   - 2: full-by-default. Optional AI workloads enabled unless the user has
 *        explicitly opted out via the desktop checkboxes.
 *
 * `enableLlmExplicit` / `enableMemoryExplicit` track whether the user has
 * actively toggled the checkbox in this install. While unset, the launcher
 * keeps the defaults aligned with `jarvis-launch.sh --full` so a stale
 * file from an old install cannot silently scale LLM/Memory to zero.
 */
data class LauncherSettings(
    val enableLlm: Boolean = true,
    val enableMemory: Boolean = true,
    val enableGpu: Boolean = true,
    val gpuMode: String = "AUTO",
    val aiAutoStart: Boolean = false,
    val enableLlmExplicit: Boolean = false,
    val enableMemoryExplicit: Boolean = false,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
    }
}

class LauncherConfig(private val configPath: Path) {
    fun load(): LauncherSettings {
        if (!Files.exists(configPath)) {
            return LauncherSettings()
        }
        val props = Properties()
        Files.newInputStream(configPath).use { props.load(it) }

        val schemaVersion = props.getProperty("schemaVersion")?.toIntOrNull() ?: 1
        val enableLlmExplicit = props.getProperty("enableLlmExplicit")?.toBoolean() ?: false
        val enableMemoryExplicit = props.getProperty("enableMemoryExplicit")?.toBoolean() ?: false
        val storedEnableLlm = props.getProperty("enableLlm")?.toBoolean()
        val storedEnableMemory = props.getProperty("enableMemory")?.toBoolean()

        // Migration: pre-v2 installs persisted LLM/Memory=false as the
        // implicit default. Drop those stale values back to the new
        // full-by-default unless the user explicitly toggled the box.
        val effectiveLlm = when {
            schemaVersion >= LauncherSettings.CURRENT_SCHEMA_VERSION && enableLlmExplicit ->
                storedEnableLlm ?: true
            else -> true
        }
        val effectiveMemory = when {
            schemaVersion >= LauncherSettings.CURRENT_SCHEMA_VERSION && enableMemoryExplicit ->
                storedEnableMemory ?: true
            else -> true
        }

        return LauncherSettings(
            enableLlm = effectiveLlm,
            enableMemory = effectiveMemory,
            enableGpu = props.getProperty("enableGpu")?.toBoolean() ?: true,
            gpuMode = props.getProperty("gpuMode") ?: "AUTO",
            aiAutoStart = props.getProperty("aiAutoStart")?.toBoolean() ?: false,
            enableLlmExplicit = enableLlmExplicit,
            enableMemoryExplicit = enableMemoryExplicit,
            schemaVersion = LauncherSettings.CURRENT_SCHEMA_VERSION
        )
    }

    fun save(settings: LauncherSettings) {
        val props = Properties()
        props["enableLlm"] = settings.enableLlm.toString()
        props["enableMemory"] = settings.enableMemory.toString()
        props["enableGpu"] = settings.enableGpu.toString()
        props["gpuMode"] = settings.gpuMode
        props["aiAutoStart"] = settings.aiAutoStart.toString()
        props["enableLlmExplicit"] = settings.enableLlmExplicit.toString()
        props["enableMemoryExplicit"] = settings.enableMemoryExplicit.toString()
        props["schemaVersion"] = settings.schemaVersion.toString()
        Files.createDirectories(configPath.parent)
        Files.newOutputStream(configPath).use { props.store(it, "Jarvis Launcher Settings") }
    }
}
