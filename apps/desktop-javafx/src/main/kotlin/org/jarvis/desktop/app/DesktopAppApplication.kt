package org.jarvis.desktop.app

import javafx.application.Application
import javafx.application.Platform
import javafx.stage.Stage
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.features.panic.PanicControlService
import org.jarvis.desktop.tray.DesktopTrayIcon
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

class DesktopAppApplication : Application() {
    private var desktopShellHost: DesktopShellHost? = null
    private var trayIcon: DesktopTrayIcon? = null
    private val logger = LoggerFactory.getLogger(DesktopAppApplication::class.java)

    /** Own executor for tray-triggered actions — independent of any shell instance's lifecycle. */
    private val trayWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-tray").apply { isDaemon = true }
    }

    override fun start(stage: Stage) {
        DesktopTlsBootstrap.configureTrustStoreIfNeeded()
        desktopShellHost = DesktopShellHost.attach(stage)
        desktopShellHost?.start()
        // Deliberately does not touch Platform.implicitExit — closing the main window keeps
        // exiting the app exactly as before. The tray is a convenience (front/panic/quit) while
        // the app is running, not a hide-to-background feature; wiring that up would require
        // reworking DesktopShellHost's close handler, which is out of scope here.
        installTrayIcon()
    }

    override fun stop() {
        trayIcon?.uninstall()
        trayIcon = null
        trayWorker.shutdownNow()
        desktopShellHost?.shutdown()
        desktopShellHost = null
    }

    private fun installTrayIcon() {
        val panicControlService = PanicControlService(ApiClient())
        val icon = DesktopTrayIcon(
            onShow = { Platform.runLater { desktopShellHost?.show() } },
            onPanic = {
                trayWorker.execute {
                    runCatching { panicControlService.engage("Panic engaged from system tray") }
                        .onFailure { e -> logger.warn("Tray panic action failed: {}", e.message) }
                }
            },
            onQuit = { Platform.runLater { Platform.exit() } }
        )
        trayIcon = icon
        if (!icon.install()) {
            logger.info("Desktop tray icon unavailable on this platform/session")
        }
    }
}

/**
 * Entry point for the Jarvis desktop SHELL (Control Center + feature screens),
 * which connects to the already-running cluster gateway. This is the app the
 * operator wants — NOT the legacy LauncherApplication that tries to bootstrap a
 * local stack and reports DEGRADED against an existing k3s deployment.
 */
fun main() {
    Application.launch(DesktopAppApplication::class.java)
}
