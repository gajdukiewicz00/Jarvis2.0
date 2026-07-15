package org.jarvis.desktop.tray

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.awt.SystemTray
import java.awt.image.BufferedImage

/**
 * Unit coverage for [DesktopTrayIcon] that does NOT require a real tray host.
 *
 * The tray install path needs an OS tray and is verified visually elsewhere
 * (hardware/desktop gated). Here we cover the platform-unsupported branch, the
 * safe uninstall no-op, and the in-process icon rendering (which needs no tray).
 */
class DesktopTrayIconTest {

    private fun newTray(): DesktopTrayIcon = DesktopTrayIcon(onShow = {}, onPanic = {}, onQuit = {})

    @Test
    fun `install returns false when the system tray is unsupported`() {
        // Only meaningful headlessly; skip on a desktop where a real tray exists
        // so this test never spawns an actual tray icon side effect.
        assumeFalse(SystemTray.isSupported(), "System tray is supported here — skipping unsupported-branch test")

        val tray = newTray()
        assertFalse(tray.install())
    }

    @Test
    fun `uninstall before install is a safe no-op`() {
        val tray = newTray()
        assertDoesNotThrow { tray.uninstall() }
    }

    @Test
    fun `renderIcon produces a 16x16 ARGB image`() {
        val tray = newTray()
        val method = DesktopTrayIcon::class.java.getDeclaredMethod("renderIcon")
        method.isAccessible = true

        val image = method.invoke(tray) as BufferedImage

        assertEquals(16, image.width)
        assertEquals(16, image.height)
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.type)
    }
}
