package org.jarvis.launcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LauncherConfigTest {

    @Test
    fun `missing file returns full-by-default settings`(@TempDir dir: Path) {
        val cfg = LauncherConfig(dir.resolve("launcher.properties"))
        val settings = cfg.load()
        assertTrue(settings.enableLlm, "enableLlm should default to true")
        assertTrue(settings.enableMemory, "enableMemory should default to true")
        assertEquals(LauncherSettings.CURRENT_SCHEMA_VERSION, settings.schemaVersion)
    }

    @Test
    fun `pre-v2 file with stale false values is migrated to full-by-default`(@TempDir dir: Path) {
        val path = dir.resolve("launcher.properties")
        Files.writeString(
            path,
            """
            |#Jarvis Launcher Settings
            |aiAutoStart=false
            |enableGpu=false
            |enableLlm=false
            |enableMemory=false
            |gpuMode=AUTO
            |""".trimMargin()
        )
        val settings = LauncherConfig(path).load()
        assertTrue(settings.enableLlm, "stale enableLlm=false should be migrated to true")
        assertTrue(settings.enableMemory, "stale enableMemory=false should be migrated to true")
        assertEquals(LauncherSettings.CURRENT_SCHEMA_VERSION, settings.schemaVersion)
        assertFalse(settings.enableLlmExplicit)
        assertFalse(settings.enableMemoryExplicit)
    }

    @Test
    fun `v2 file with explicit user choice is respected`(@TempDir dir: Path) {
        val path = dir.resolve("launcher.properties")
        Files.writeString(
            path,
            """
            |#Jarvis Launcher Settings
            |aiAutoStart=false
            |enableGpu=true
            |enableLlm=false
            |enableMemory=false
            |enableLlmExplicit=true
            |enableMemoryExplicit=true
            |gpuMode=AUTO
            |schemaVersion=2
            |""".trimMargin()
        )
        val settings = LauncherConfig(path).load()
        assertFalse(settings.enableLlm, "explicit user opt-out must be honored")
        assertFalse(settings.enableMemory, "explicit user opt-out must be honored")
        assertTrue(settings.enableLlmExplicit)
        assertTrue(settings.enableMemoryExplicit)
    }

    @Test
    fun `v2 file with non-explicit stale false values keeps full defaults`(@TempDir dir: Path) {
        val path = dir.resolve("launcher.properties")
        Files.writeString(
            path,
            """
            |#Jarvis Launcher Settings
            |aiAutoStart=false
            |enableGpu=false
            |enableLlm=false
            |enableMemory=false
            |enableLlmExplicit=false
            |enableMemoryExplicit=false
            |gpuMode=AUTO
            |schemaVersion=2
            |""".trimMargin()
        )
        val settings = LauncherConfig(path).load()
        assertTrue(settings.enableLlm, "non-explicit stale enableLlm=false should not disable full launch")
        assertTrue(settings.enableMemory, "non-explicit stale enableMemory=false should not disable full launch")
        assertFalse(settings.enableLlmExplicit)
        assertFalse(settings.enableMemoryExplicit)
    }

    @Test
    fun `save round trip persists schema version and explicit markers`(@TempDir dir: Path) {
        val path = dir.resolve("launcher.properties")
        val cfg = LauncherConfig(path)
        cfg.save(
            LauncherSettings(
                enableLlm = false,
                enableMemory = true,
                enableGpu = true,
                gpuMode = "AUTO",
                aiAutoStart = false,
                enableLlmExplicit = true,
                enableMemoryExplicit = true
            )
        )
        val reloaded = cfg.load()
        assertFalse(reloaded.enableLlm)
        assertTrue(reloaded.enableMemory)
        assertTrue(reloaded.enableLlmExplicit)
        assertTrue(reloaded.enableMemoryExplicit)
        assertEquals(LauncherSettings.CURRENT_SCHEMA_VERSION, reloaded.schemaVersion)
    }
}
