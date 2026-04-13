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
