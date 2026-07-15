package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SystemAppIndexTest {

    private fun writeFixtures(dir: Path) {
        writeDesktop(
            dir, "code.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Visual Studio Code
            GenericName=Text Editor
            Comment=Code Editing. Redefined.
            Exec=/usr/share/code/code --unity-launch %F
            Keywords=vscode;editor;
            Categories=Development;IDE;
            StartupWMClass=Code
            """,
        )
        writeDesktop(
            dir, "org.telegram.desktop.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Telegram Desktop
            GenericName=Telegram
            Comment=Official Telegram messenger
            Exec=telegram-desktop -- %u
            Keywords=tg;chat;messenger;
            Categories=Network;InstantMessaging;
            StartupWMClass=TelegramDesktop
            """,
        )
        writeDesktop(
            dir, "org.gnome.Calculator.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Calculator
            GenericName=Calculator
            Comment=Perform arithmetic
            Exec=gnome-calculator %U
            Keywords=calculation;
            Categories=GNOME;GTK;Utility;
            """,
        )
        // NoDisplay entry must be skipped by the parser.
        writeDesktop(
            dir, "org.hidden.tool.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Hidden Tool
            Exec=hidden-tool
            NoDisplay=true
            """,
        )
    }

    private fun writeDesktop(dir: Path, fileName: String, content: String) {
        Files.writeString(dir.resolve(fileName), content.trimIndent())
    }

    private fun fixtureIndex(dir: Path): SystemAppIndex {
        writeFixtures(dir)
        return SystemAppIndex(scanDirs = listOf(dir), pathDirs = emptyList(), indexPathExecutables = false)
    }

    @Test
    fun `parses application entries and skips NoDisplay`(@TempDir dir: Path) {
        val index = fixtureIndex(dir)
        val ids = index.allEntries().map { it.id }.toSet()

        assertEquals(3, index.allEntries().size, "NoDisplay entry must be skipped")
        assertTrue(ids.containsAll(setOf("code", "org.telegram.desktop", "org.gnome.Calculator")))
        assertFalse(ids.contains("org.hidden.tool"), "Hidden Tool has NoDisplay=true")
    }

    @Test
    fun `resolves phonetic VS Code to launch`(@TempDir dir: Path) {
        val index = fixtureIndex(dir)
        val result = assertInstanceOf(AppResolution.Launch::class.java, index.resolve("вес скотт"))

        assertEquals("code", result.entry.id)
        assertEquals(listOf("gtk-launch", "code"), index.launchCommand(result.entry))
    }

    @Test
    fun `resolves telegram alias to launch`(@TempDir dir: Path) {
        val index = fixtureIndex(dir)
        val result = assertInstanceOf(AppResolution.Launch::class.java, index.resolve("телеграм"))

        assertEquals("org.telegram.desktop", result.entry.id)
        assertTrue(result.entry.name.contains("Telegram"))
    }

    @Test
    fun `resolves calculator alias to launch`(@TempDir dir: Path) {
        val index = fixtureIndex(dir)
        val result = assertInstanceOf(AppResolution.Launch::class.java, index.resolve("калькулятор"))

        assertEquals("org.gnome.Calculator", result.entry.id)
    }

    @Test
    fun `partial vs code token resolves to VS Code (launch or clarify, never not found)`(@TempDir dir: Path) {
        val index = fixtureIndex(dir)
        val result = index.resolve("скотт")

        val mentionsVsCode = when (result) {
            is AppResolution.Launch -> result.entry.id == "code"
            is AppResolution.Clarify -> result.candidates.any { it.id == "code" }
            is AppResolution.NotFound -> false
        }
        assertTrue(mentionsVsCode, "expected VS Code via Launch or Clarify, got $result")
    }

    @Test
    fun `unrelated word is not confidently matched`(@TempDir dir: Path) {
        val index = fixtureIndex(dir)
        val result = assertInstanceOf(AppResolution.NotFound::class.java, index.resolve("вексель"))

        assertTrue(result.suggestions.size <= 3)
    }

    @Test
    fun `dangerous PATH binary is never launched`(@TempDir fixtures: Path, @TempDir bin: Path) {
        writeFixtures(fixtures)
        val rm = bin.resolve("rm")
        Files.writeString(rm, "#!/bin/sh\n")
        assertTrue(rm.toFile().setExecutable(true), "test setup: rm must be executable")

        val index = SystemAppIndex(
            scanDirs = listOf(fixtures),
            pathDirs = listOf(bin),
            indexPathExecutables = true,
        )

        assertFalse(index.resolve("rm") is AppResolution.Launch, "rm must never resolve to Launch")
        assertTrue(index.isDangerous("rm"))
        assertTrue(index.isDangerous("mkfs.ext4"))
        assertTrue(index.isDangerous("/usr/bin/sudo apt update"))
        assertFalse(index.isDangerous("gnome-calculator"))
    }

    @Test
    fun `launch command for a flatpak entry uses flatpak run`() {
        val index = SystemAppIndex(scanDirs = emptyList(), pathDirs = emptyList(), indexPathExecutables = false)
        val flatpak = AppEntry(
            id = "org.telegram.desktop",
            name = "Telegram Desktop",
            exec = "",
            source = AppSource.FLATPAK,
            launchMethod = LaunchMethod.FLATPAK_RUN,
        )

        assertEquals(listOf("flatpak", "run", "org.telegram.desktop"), index.launchCommand(flatpak))
    }
}
