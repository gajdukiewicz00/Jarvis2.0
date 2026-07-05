package org.jarvis.desktop.tray

import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Optional OS-level system tray icon with a Show / Panic / Quit menu.
 *
 * Uses `java.awt.SystemTray` (not JavaFX, which has no first-class tray API)
 * and degrades gracefully — [install] is a no-op that returns `false` when
 * [SystemTray.isSupported] is false (headless CI, most Linux window
 * managers without a tray host, etc.), so callers never need to branch on
 * platform support themselves.
 *
 * Requires a real desktop/tray host to verify visually — see hardwareGated.
 */
class DesktopTrayIcon(
    private val onShow: () -> Unit,
    private val onPanic: () -> Unit,
    private val onQuit: () -> Unit
) {
    private val logger = LoggerFactory.getLogger(DesktopTrayIcon::class.java)
    private var trayIcon: TrayIcon? = null

    /** Returns true if the tray icon was installed, false if unsupported or it failed. */
    fun install(): Boolean {
        if (!SystemTray.isSupported()) {
            logger.info("System tray is not supported on this platform — skipping tray icon")
            return false
        }
        if (trayIcon != null) {
            return true
        }

        return try {
            val popup = PopupMenu()
            popup.add(MenuItem("Show Jarvis").apply { addActionListener { onShow() } })
            popup.add(MenuItem("Panic").apply { addActionListener { onPanic() } })
            popup.addSeparator()
            popup.add(MenuItem("Quit").apply { addActionListener { onQuit() } })

            val icon = TrayIcon(renderIcon(), "Jarvis Desktop", popup).apply {
                isImageAutoSize = true
                addActionListener { onShow() }
            }

            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            true
        } catch (e: Exception) {
            logger.warn("Failed to install system tray icon: {}", e.message)
            trayIcon = null
            false
        }
    }

    fun uninstall() {
        val icon = trayIcon ?: return
        runCatching { SystemTray.getSystemTray().remove(icon) }
        trayIcon = null
    }

    /** Renders a minimal glyph in-process so no bundled icon asset is required. */
    private fun renderIcon(): BufferedImage {
        val size = 16
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0x7C, 0xC7, 0xFF)
            g.fillOval(1, 1, size - 2, size - 2)
            g.color = Color(0x0B, 0x0F, 0x14)
            g.fillOval(size / 2 - 2, size / 2 - 2, 4, 4)
        } finally {
            g.dispose()
        }
        return image
    }
}
