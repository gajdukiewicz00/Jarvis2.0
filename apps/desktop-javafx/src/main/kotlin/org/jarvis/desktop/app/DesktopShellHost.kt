package org.jarvis.desktop.app

import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.controller.LoginController
import org.jarvis.desktop.shell.ShellRoot
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

class DesktopShellHost private constructor(
    private val stage: Stage,
    private val onClosed: (() -> Unit)?
) {
    private var shellRoot: ShellRoot? = null

    fun start() {
        ensureStageCloseHandler()
        if (TokenManager.isAuthenticated()) {
            showShell(stage)
        } else {
            showLoginScreen(stage)
        }
    }

    fun shutdown() {
        shellRoot?.shutdown()
        shellRoot = null
        LoginController.loginSuccessHandler = null
    }

    fun close() {
        shutdown()
        if (stage.isShowing) {
            stage.close()
            return
        }
        stage.properties.remove(HOST_KEY)
        onClosed?.invoke()
    }

    fun show() {
        if (!stage.isShowing) {
            stage.show()
        }
        stage.toFront()
        stage.requestFocus()
    }

    private fun ensureStageCloseHandler() {
        stage.setOnCloseRequest {
            shutdown()
            stage.properties.remove(HOST_KEY)
            onClosed?.invoke()
        }
    }

    private fun showShell(targetStage: Stage) {
        LoginController.loginSuccessHandler = null
        shellRoot?.shutdown()

        val root = ShellRoot(
            onLogoutRequested = { showLoginScreen(targetStage) }
        )
        shellRoot = root

        val scene = Scene(root, 1320.0, 860.0)
        scene.stylesheets += requireNotNull(javaClass.getResource("/css/shell.css")) {
            "Shell stylesheet not found"
        }.toExternalForm()

        targetStage.title = buildStageTitle()
        targetStage.scene = scene
        targetStage.minWidth = 1080.0
        targetStage.minHeight = 720.0
        targetStage.sizeToScene()
        if (!targetStage.isShowing) {
            targetStage.show()
        }
        targetStage.toFront()
    }

    private fun showLoginScreen(targetStage: Stage) {
        shellRoot?.shutdown()
        shellRoot = null

        LoginController.loginSuccessHandler = { loginStage ->
            showShell(loginStage)
        }

        val loader = FXMLLoader(requireNotNull(javaClass.getResource("/fxml/LoginView.fxml")) {
            "Login view not found"
        })
        val scene = Scene(loader.load(), 600.0, 500.0)

        targetStage.title = "Jarvis Desktop - Sign in"
        targetStage.scene = scene
        targetStage.minWidth = 600.0
        targetStage.minHeight = 500.0
        targetStage.sizeToScene()
        if (!targetStage.isShowing) {
            targetStage.show()
        }
        targetStage.toFront()
    }

    private fun buildStageTitle(): String {
        val username = TokenManager.getUsername()?.takeIf { it.isNotBlank() } ?: "Offline user"
        return "Jarvis Desktop - $username"
    }

    companion object {
        private const val HOST_KEY = "jarvis.desktop.shell.host"

        fun attach(stage: Stage, onClosed: (() -> Unit)? = null): DesktopShellHost {
            val existing = stage.properties[HOST_KEY] as? DesktopShellHost
            if (existing != null) {
                return existing
            }
            return DesktopShellHost(stage, onClosed).also { host ->
                stage.properties[HOST_KEY] = host
            }
        }

        fun showAuthenticated(stage: Stage) {
            attach(stage).run {
                ensureStageCloseHandler()
                showShell(stage)
            }
        }
    }
}

object DesktopTlsBootstrap {
    private val logger = LoggerFactory.getLogger("DesktopAppTlsBootstrap")

    fun configureTrustStoreIfNeeded() {
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

        logger.info("Configured desktop-javafx TLS truststore: {}", trustStorePath.toAbsolutePath())
    }
}
