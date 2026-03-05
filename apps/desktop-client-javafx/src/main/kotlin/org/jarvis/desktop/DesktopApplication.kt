package org.jarvis.desktop

import javafx.application.Application
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.TabPane
import javafx.stage.Stage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.auth.AuthResponse
import org.jarvis.desktop.auth.LoginRequest
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.service.AuthService
import org.jarvis.desktop.service.PcControlWebSocketClient
import org.jarvis.desktop.service.SystemControlService
import org.jarvis.desktop.ui.tabs.*
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths

class DesktopApplication : Application() {
    private val logger = LoggerFactory.getLogger(DesktopApplication::class.java)
    private val apiGatewayBase = AppConfig.apiGatewayBaseUrl
    private val apiBaseUrl = AppConfig.apiBaseUrl
    private val objectMapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder().build()
    
    // PC Control services
    private val systemControlService = SystemControlService()
    private var pcWebSocketClient: PcControlWebSocketClient? = null

    override fun start(stage: Stage) {
        logger.info("🚀 Starting Jarvis 2.0 Desktop Application...")
        
        // Development mode auto-login
        val devUsername = System.getenv("JARVIS_DEV_USER")
        val devPassword = System.getenv("JARVIS_DEV_PASSWORD")
        
        if (!TokenManager.isAuthenticated()) {
            if (devUsername != null && devPassword != null) {
                logger.info("Development mode: attempting auto-login for user: $devUsername")
                tryAutoLogin(devUsername, devPassword, stage)
            } else {
                logger.info("User not authenticated, showing login screen")
                showLoginScreen(stage)
            }
        } else {
            logger.info("User authenticated as: ${TokenManager.getUsername()}")
            // Verify token is still valid by making a test request
            verifyAuthenticationAndStart(stage)
        }
    }
    
    private fun showLoginScreen(stage: Stage) {
        try {
            val fxmlLoader = FXMLLoader(javaClass.getResource("/fxml/LoginView.fxml"))
            val scene = Scene(fxmlLoader.load(), 600.0, 500.0)
            
            stage.title = "Jarvis 2.0 - Вход"
            stage.scene = scene
            stage.show()
        } catch (e: Exception) {
            logger.error("Failed to load login screen", e)
            throw e
        }
    }
    
    private fun showMainApplication(stage: Stage) {
        logger.info("📋 Loading application tabs...")
        
        val authService = AuthService(baseUrl = apiGatewayBase)
        val apiClient = ApiClient(baseUrl = apiBaseUrl, authService = authService)
        
        val tabPane = TabPane()
        val settingsTab = SettingsTab(onLogout = { handleLogout(stage) })

        tabPane.tabs.addAll(
            HomeTab(apiClient).tab,
            VoiceTab(apiClient).tab,
            DevicesTab(apiClient).tab,
            PcControlTab(apiClient).tab,
            LifeTab(apiClient).tab,
            AnalyticsTab(apiClient).tab,
            settingsTab.tab
        )
        logger.info("✓ All tabs loaded successfully")

        val scene = Scene(tabPane, 1024.0, 768.0)
        stage.title = "Jarvis 2.0 - ${TokenManager.getUsername()}"
        stage.scene = scene
        stage.show()
        
        // Initialize PC Control WebSocket connection
        initPcControlWebSocket()
        
        // Handle window close
        stage.setOnCloseRequest {
            logger.info("🛑 Application closing, cleaning up...")
            pcWebSocketClient?.disconnect()
            Platform.exit()
        }
        
        logger.info("✅ Jarvis 2.0 Desktop Application started successfully!")
    }
    
    private fun initPcControlWebSocket() {
        // Get API Gateway URL for WebSocket
        val wsUrl = AppConfig.pcControlWebSocketUrl

        pcWebSocketClient = PcControlWebSocketClient(
            url = wsUrl,
            systemControl = systemControlService,
            onStatusChange = { status ->
                logger.debug("PC WebSocket status: $status")
            }
        )
        
        // Connect in background
        Thread {
            try {
                Thread.sleep(2000) // Wait for app to fully initialize
                pcWebSocketClient?.connect()
            } catch (e: Exception) {
                logger.warn("Failed to connect PC Control WebSocket: ${e.message}")
            }
        }.start()
        
        logger.info("🔌 PC Control WebSocket initialized")
    }

    private fun tryAutoLogin(username: String, password: String, stage: Stage) {
        Thread {
            try {
                logger.info("🔐 Development auto-login for: $username")
                
                val loginRequest = LoginRequest(username, password)
                val requestBody = objectMapper.writeValueAsString(loginRequest)
                
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$apiGatewayBase/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()
                
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                
                if (response.statusCode() == 200) {
                    val authResponse: AuthResponse = objectMapper.readValue(response.body())
                    
                    TokenManager.saveTokens(
                        authResponse.accessToken,
                        authResponse.refreshToken,
                        authResponse.username,
                        authResponse.role
                    )
                    
                    logger.info("✅ Auto-login successful for: ${authResponse.username}")
                    Platform.runLater { showMainApplication(stage) }
                } else {
                    logger.warn("⚠️ Auto-login failed: ${response.statusCode()}")
                    Platform.runLater { showLoginScreen(stage) }
                }
            } catch (e: Exception) {
                logger.warn("⚠️ Auto-login error: ${e.message}")
                Platform.runLater { showLoginScreen(stage) }
            }
        }.start()
    }
    
    private fun verifyAuthenticationAndStart(stage: Stage) {
        Thread {
            try {
                logger.info("🔍 Verifying authentication token...")
                
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$apiGatewayBase/api/v1/security/auth/me"))
                    .header("Authorization", "Bearer ${TokenManager.getAccessToken()}")
                    .GET()
                    .build()
                
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                
                if (response.statusCode() == 200) {
                    logger.info("✅ Token verified successfully")
                    Platform.runLater { showMainApplication(stage) }
                } else if (response.statusCode() == 401) {
                    logger.warn("⚠️ Token verification failed: ${response.statusCode()}, clearing tokens")
                    TokenManager.clearTokens()
                    Platform.runLater { showLoginScreen(stage) }
                } else if (response.statusCode() >= 500) {
                    logger.warn("⚠️ Auth check failed with status ${response.statusCode()}, server unavailable")
                    TokenManager.clearTokens()
                    Platform.runLater {
                        Alert(Alert.AlertType.ERROR).apply {
                            title = "Jarvis 2.0"
                            headerText = "Сервер недоступен"
                            contentText = "Попробуйте позже"
                        }.showAndWait()
                        showLoginScreen(stage)
                    }
                } else {
                    logger.warn("⚠️ Auth check unexpected status ${response.statusCode()}, treating as unauthorized")
                    TokenManager.clearTokens()
                    Platform.runLater { showLoginScreen(stage) }
                }
            } catch (e: Exception) {
                logger.warn("⚠️ Token verification error: ${e.message}, server may be unavailable")
                TokenManager.clearTokens()
                Platform.runLater {
                    Alert(Alert.AlertType.ERROR).apply {
                        title = "Jarvis 2.0"
                        headerText = "Сервер недоступен"
                        contentText = "Попробуйте позже"
                    }.showAndWait()
                    showLoginScreen(stage)
                }
            }
        }.start()
    }

    private fun handleLogout(stage: Stage) {
        logger.info("🔒 Logging out current user")
        pcWebSocketClient?.disconnect()
        TokenManager.clearTokens()
        showLoginScreen(stage)
    }
}

private fun configureDesktopTrustStore() {
    val logger = LoggerFactory.getLogger("DesktopTlsBootstrap")

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

    val trustStorePath = fallbackPaths.firstOrNull { Files.isRegularFile(it) }
    if (trustStorePath == null) {
        logger.warn("TLS truststore not found. Looked in {}", fallbackPaths.joinToString())
        return
    }

    val trustStorePassword = System.getenv("JARVIS_JAVA_TRUSTSTORE_PASSWORD")
        ?.takeIf { it.isNotBlank() }
        ?: "changeit"

    System.setProperty("javax.net.ssl.trustStore", trustStorePath.toAbsolutePath().toString())
    if (System.getProperty("javax.net.ssl.trustStorePassword").isNullOrBlank()) {
        System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword)
    }

    logger.info("Configured TLS truststore: {}", trustStorePath.toAbsolutePath())
}

fun main() {
    configureDesktopTrustStore()
    Application.launch(DesktopApplication::class.java)
}
