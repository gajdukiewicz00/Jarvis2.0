package org.jarvis.desktop.app

import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.controller.LoginController
import org.jarvis.desktop.shell.ShellRoot
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

class DesktopAppApplication : Application() {
    private var shellRoot: ShellRoot? = null

    override fun start(stage: Stage) {
        stage.setOnCloseRequest {
            shellRoot?.shutdown()
        }

        if (TokenManager.isAuthenticated()) {
            showShell(stage)
        } else {
            showLoginScreen(stage)
        }
    }

    override fun stop() {
        shellRoot?.shutdown()
        shellRoot = null
    }

    private fun showShell(stage: Stage) {
        LoginController.loginSuccessHandler = null
        shellRoot?.shutdown()

        val root = ShellRoot(
            onLogoutRequested = { showLoginScreen(stage) }
        )
        shellRoot = root

        val scene = Scene(root, 1320.0, 860.0)
        scene.stylesheets += requireNotNull(javaClass.getResource("/css/shell.css")) {
            "Shell stylesheet not found"
        }.toExternalForm()

        stage.title = buildStageTitle()
        stage.scene = scene
        stage.minWidth = 1080.0
        stage.minHeight = 720.0
        stage.sizeToScene()
        if (!stage.isShowing) {
            stage.show()
        }
    }

    private fun showLoginScreen(stage: Stage) {
        shellRoot?.shutdown()
        shellRoot = null

        LoginController.loginSuccessHandler = { loginStage ->
            showShell(loginStage)
        }

        val loader = FXMLLoader(requireNotNull(javaClass.getResource("/fxml/LoginView.fxml")) {
            "Login view not found"
        })
        val scene = Scene(loader.load(), 600.0, 500.0)

        stage.title = "Jarvis Desktop - Sign in"
        stage.scene = scene
        stage.minWidth = 600.0
        stage.minHeight = 500.0
        stage.sizeToScene()
        if (!stage.isShowing) {
            stage.show()
        }
    }

    private fun buildStageTitle(): String {
        val username = TokenManager.getUsername()?.takeIf { it.isNotBlank() } ?: "Offline user"
        return "Jarvis Desktop - $username"
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
