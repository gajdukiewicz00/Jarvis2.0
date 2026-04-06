package org.jarvis.desktop.app

import javafx.application.Application
import javafx.scene.Scene
import javafx.stage.Stage
import org.jarvis.desktop.shell.ShellRoot
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

class DesktopAppApplication : Application() {
    private var shellRoot: ShellRoot? = null

    override fun start(stage: Stage) {
        val root = ShellRoot()
        shellRoot = root

        val scene = Scene(root, 1320.0, 860.0)
        scene.stylesheets += requireNotNull(javaClass.getResource("/css/shell.css")) {
            "Shell stylesheet not found"
        }.toExternalForm()

        stage.title = "Jarvis Desktop"
        stage.scene = scene
        stage.minWidth = 1080.0
        stage.minHeight = 720.0
        stage.setOnCloseRequest {
            shellRoot?.shutdown()
        }
        stage.show()
    }

    override fun stop() {
        shellRoot?.shutdown()
        shellRoot = null
    }
}

private fun configureDesktopTrustStore() {
    val logger = LoggerFactory.getLogger("DesktopAppTlsBootstrap")

    if (!System.getProperty("javax.net.ssl.trustStore").isNullOrBlank()) {
        return
    }

    val envPath = System.getenv("JARVIS_JAVA_TRUSTSTORE")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { Paths.get(it) }

    val userHome = System.getProperty("user.home")
    val fallbackPaths = listOfNotNull(
        envPath,
        Paths.get(userHome, ".jarvis", "tls", "jarvis-cacerts.jks"),
        Paths.get(userHome, ".jarvis", "certs", "jarvis-truststore.jks")
    )

    val trustStorePath = fallbackPaths.firstOrNull { Files.isRegularFile(it) } ?: return
    val trustStorePassword = System.getenv("JARVIS_JAVA_TRUSTSTORE_PASSWORD")
        ?.takeIf { it.isNotBlank() }
        ?: "changeit"

    System.setProperty("javax.net.ssl.trustStore", trustStorePath.toAbsolutePath().toString())
    if (System.getProperty("javax.net.ssl.trustStorePassword").isNullOrBlank()) {
        System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword)
    }

    logger.info("Configured desktop-app TLS truststore: {}", trustStorePath.toAbsolutePath())
}

fun main() {
    configureDesktopTrustStore()
    Application.launch(DesktopAppApplication::class.java)
}
