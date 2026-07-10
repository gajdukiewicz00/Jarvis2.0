package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the [SystemControlService.openApp] fuzzy-index branch and the wmctrl-based window
 * restore/maximize/show-desktop commands. All paths are exercised with injected test seams
 * (a fake [SystemControlService.AppOpener] and a captured command executor) so nothing scans the
 * real filesystem or spawns an OS process — the tests are deterministic and offline.
 */
class SystemControlServiceAppIndexTest {

    private fun entry(name: String, id: String = name): AppEntry =
        AppEntry(
            id = id,
            name = name,
            exec = "",
            source = AppSource.DESKTOP,
            launchMethod = LaunchMethod.GTK_LAUNCH,
        )

    private class FakeAppOpener(
        private val resolution: AppResolution,
        private val launchArgv: List<String> = listOf("gtk-launch", "fake"),
    ) : SystemControlService.AppOpener {
        override fun resolve(query: String): AppResolution = resolution
        override fun launchCommand(entry: AppEntry): List<String> = launchArgv
    }

    // ==================== openApp index branch ====================

    @Test
    fun `openApp launches the resolved app for an unknown spoken name`() {
        val service = SystemControlService()
        val calculator = entry(name = "Calculator", id = "org.gnome.Calculator")
        service.appOpenerOverride = FakeAppOpener(
            resolution = AppResolution.Launch(calculator, confidence = 0.97),
            launchArgv = listOf("gtk-launch", "org.gnome.Calculator"),
        )
        val launched = mutableListOf<List<String>>()
        service.appLauncherOverride = { argv -> launched.add(argv) }

        val result = service.openApp("калькулятор")

        assertTrue(result.isSuccess, "a resolved Launch must succeed")
        assertEquals(listOf("gtk-launch", "org.gnome.Calculator"), launched.firstOrNull())
    }

    @Test
    fun `openApp returns coded APP_CLARIFY and never launches on an ambiguous name`() {
        val service = SystemControlService()
        service.appOpenerOverride = FakeAppOpener(
            resolution = AppResolution.Clarify(
                candidates = listOf(entry("VS Code", "code"), entry("VLC", "vlc")),
                confidences = listOf(0.70, 0.66),
            ),
        )
        val launched = mutableListOf<List<String>>()
        service.appLauncherOverride = { argv -> launched.add(argv) }

        val result = service.openApp("скотт")

        assertTrue(result.isFailure)
        assertInstanceOf(AppOpenException::class.java, result.exceptionOrNull())
        assertEquals("APP_CLARIFY|VS Code", result.exceptionOrNull()?.message)
        assertTrue(launched.isEmpty(), "Clarify must never launch anything")
    }

    @Test
    fun `openApp returns coded APP_NOT_FOUND with the suggestion names`() {
        val service = SystemControlService()
        service.appOpenerOverride = FakeAppOpener(
            resolution = AppResolution.NotFound(
                query = "вексель",
                suggestions = listOf(entry("VS Code", "code"), entry("Excel", "excel")),
            ),
        )

        val result = service.openApp("вексель")

        assertTrue(result.isFailure)
        assertEquals("APP_NOT_FOUND|вексель|VS Code,Excel", result.exceptionOrNull()?.message)
    }

    @Test
    fun `openApp emits APP_NOT_FOUND without a suffix when there are no suggestions`() {
        val service = SystemControlService()
        service.appOpenerOverride = FakeAppOpener(
            resolution = AppResolution.NotFound(query = "zzz", suggestions = emptyList()),
        )

        val result = service.openApp("zzz")

        assertEquals("APP_NOT_FOUND|zzz", result.exceptionOrNull()?.message)
    }

    @Test
    fun `openApp keeps the hardcoded fast-path and never consults the index`() {
        val service = SystemControlService()
        // If the fast-path wrongly fell through to the index, this NotFound fake would fail it.
        service.appOpenerOverride = FakeAppOpener(AppResolution.NotFound("unused", emptyList()))
        val launched = mutableListOf<List<String>>()
        service.appLauncherOverride = { argv -> launched.add(argv) }

        val result = service.openApp("firefox")

        assertTrue(result.isSuccess, "a known key must launch via the fast-path")
        assertEquals(listOf("firefox"), launched.firstOrNull())
    }

    // ==================== window restore / maximize / show-desktop argv ====================

    @Test
    fun `restoreWindows issues wmctrl -k off`() {
        val service = SystemControlService()
        val captured = mutableListOf<List<String>>()
        service.commandExecutorOverride = { argv -> captured.add(argv); 0 to "" }

        val result = service.restoreWindows()

        assertTrue(result.isSuccess)
        assertEquals(listOf("wmctrl", "-k", "off"), captured.single())
    }

    @Test
    fun `restoreWindows reports WM_UNSUPPORTED when wmctrl fails`() {
        val service = SystemControlService()
        service.commandExecutorOverride = { _ -> 127 to "wmctrl: command not found" }

        val result = service.restoreWindows()

        assertTrue(result.isFailure)
        assertEquals("WM_UNSUPPORTED", result.exceptionOrNull()?.message)
    }

    @Test
    fun `maximizeActiveWindow issues the wmctrl maximize argv`() {
        val service = SystemControlService()
        val captured = mutableListOf<List<String>>()
        service.commandExecutorOverride = { argv -> captured.add(argv); 0 to "" }

        val result = service.maximizeActiveWindow()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("wmctrl", "-r", ":ACTIVE:", "-b", "add,maximized_vert,maximized_horz"),
            captured.single(),
        )
    }

    @Test
    fun `maximizeActiveWindow falls back to xdotool when wmctrl fails`() {
        val service = SystemControlService()
        val captured = mutableListOf<List<String>>()
        service.commandExecutorOverride = { argv ->
            captured.add(argv)
            if (argv.firstOrNull() == "wmctrl") 1 to "no wmctrl" else 0 to ""
        }

        val result = service.maximizeActiveWindow()

        assertTrue(result.isSuccess)
        assertEquals(listOf("xdotool", "getactivewindow", "windowmaximize"), captured.last())
    }

    @Test
    fun `showDesktop issues wmctrl -k on`() {
        val service = SystemControlService()
        val captured = mutableListOf<List<String>>()
        service.commandExecutorOverride = { argv -> captured.add(argv); 0 to "" }

        val result = service.showDesktop()

        assertTrue(result.isSuccess)
        assertEquals(listOf("wmctrl", "-k", "on"), captured.single())
    }
}
