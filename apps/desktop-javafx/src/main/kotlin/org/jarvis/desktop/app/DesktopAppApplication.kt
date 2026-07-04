package org.jarvis.desktop.app

import javafx.application.Application
import javafx.stage.Stage

class DesktopAppApplication : Application() {
    private var desktopShellHost: DesktopShellHost? = null

    override fun start(stage: Stage) {
        DesktopTlsBootstrap.configureTrustStoreIfNeeded()
        desktopShellHost = DesktopShellHost.attach(stage)
        desktopShellHost?.start()
    }

    override fun stop() {
        desktopShellHost?.shutdown()
        desktopShellHost = null
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
