package org.jarvis.desktop.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.control.*
import javafx.stage.Stage
import org.jarvis.desktop.auth.*
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class LoginController {
    companion object {
        @Volatile
        var loginSuccessHandler: ((Stage) -> Unit)? = null
    }

    
    private val logger = LoggerFactory.getLogger(LoginController::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder().build()
    
    // Login tab controls
    @FXML private lateinit var loginUsername: TextField
    @FXML private lateinit var loginPassword: PasswordField
    @FXML private lateinit var loginButton: Button
    @FXML private lateinit var loginError: Label
    
    // Register tab controls
    @FXML private lateinit var registerUsername: TextField
    @FXML private lateinit var registerPassword: PasswordField
    @FXML private lateinit var registerPasswordConfirm: PasswordField
    @FXML private lateinit var registerButton: Button
    @FXML private lateinit var registerError: Label
    
    @FXML private lateinit var tabPane: TabPane
    
    @FXML
    fun initialize() {
        logger.info("LoginController initialized")
        
        // Add enter key listeners
        loginPassword.setOnAction { handleLogin() }
        registerPasswordConfirm.setOnAction { handleRegister() }
    }
    
    @FXML
    fun handleLogin() {
        val username = loginUsername.text?.trim()
        val password = loginPassword.text
        
        // Validation
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            showLoginError("Пожалуйста, заполните все поля")
            return
        }
        
        loginButton.isDisable = true
        loginError.isVisible = false
        
        Thread {
            var resolvedConfig: ResolvedDesktopConfig? = null
            try {
                logger.info("Attempting login for user: $username")
                resolvedConfig = AppConfig.current()
                val baseUrl = resolvedConfig.apiGatewayBaseUrl
                
                val loginRequest = LoginRequest(username, password)
                val requestBody = objectMapper.writeValueAsString(loginRequest)
                
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$baseUrl/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()
                
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                
                if (response.statusCode() == 200) {
                    val authResponse: AuthResponse = objectMapper.readValue(response.body())
                    
                    // Save tokens
                    TokenManager.saveTokens(
                        authResponse.accessToken,
                        authResponse.refreshToken,
                        authResponse.username,
                        authResponse.role
                    )
                    
                    logger.info("✅ Login successful for user: ${authResponse.username}")
                    
                    // Navigate to main application
                    Platform.runLater {
                        navigateToMainApp()
                    }
                } else {
                    val errorMsg = try {
                        val error = objectMapper.readTree(response.body())
                        error.get("error")?.asText() ?: "Ошибка аутентификации"
                    } catch (e: Exception) {
                        "Ошибка аутентификации"
                    }
                    
                    logger.error("Login failed: $errorMsg")
                    Platform.runLater {
                        showLoginError(errorMsg)
                        loginButton.isDisable = false
                    }
                }
            } catch (e: Exception) {
                logger.error("Login error: ${e.message}", e)
                Platform.runLater {
                    val config = resolvedConfig ?: AppConfig.current()
                    showLoginError(ServerConnectionErrorFormatter.format(config, e))
                    loginButton.isDisable = false
                }
            }
        }.start()
    }
    
    @FXML
    fun handleRegister() {
        val username = registerUsername.text?.trim()
        val password = registerPassword.text
        val passwordConfirm = registerPasswordConfirm.text
        
        // Validation
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            showRegisterError("Пожалуйста, заполните все поля")
            return
        }
        
        if (password != passwordConfirm) {
            showRegisterError("Пароли не совпадают")
            return
        }
        
        if (password.length < 6) {
            showRegisterError("Пароль должен быть не менее 6 символов")
            return
        }
        
        registerButton.isDisable = true
        registerError.isVisible = false
        
        Thread {
            var resolvedConfig: ResolvedDesktopConfig? = null
            try {
                logger.info("Attempting registration for user: $username")
                resolvedConfig = AppConfig.current()
                val baseUrl = resolvedConfig.apiGatewayBaseUrl
                
                val registerRequest = RegisterRequest(username, password, "USER")
                val requestBody = objectMapper.writeValueAsString(registerRequest)
                
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$baseUrl/auth/register"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()
                
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                
                if (response.statusCode() in 200..299) {
                    val authResponse: AuthResponse = objectMapper.readValue(response.body())
                    
                    // Save tokens
                    TokenManager.saveTokens(
                        authResponse.accessToken,
                        authResponse.refreshToken,
                        authResponse.username,
                        authResponse.role
                    )
                    
                    logger.info("✅ Registration successful for user: ${authResponse.username}")
                    
                    // Navigate to main application
                    Platform.runLater {
                        navigateToMainApp()
                    }
                } else {
                    val errorMsg = try {
                        val error = objectMapper.readTree(response.body())
                        error.get("error")?.asText() ?: "Ошибка регистрации"
                    } catch (e: Exception) {
                        "Ошибка регистрации"
                    }
                    
                    logger.error("Registration failed: $errorMsg")
                    Platform.runLater {
                        showRegisterError(errorMsg)
                        registerButton.isDisable = false
                    }
                }
            } catch (e: Exception) {
                logger.error("Registration error: ${e.message}", e)
                Platform.runLater {
                    val config = resolvedConfig ?: AppConfig.current()
                    showRegisterError(ServerConnectionErrorFormatter.format(config, e))
                    registerButton.isDisable = false
                }
            }
        }.start()
    }
    
    private fun showLoginError(message: String) {
        loginError.text = message
        loginError.isVisible = true
    }
    
    private fun showRegisterError(message: String) {
        registerError.text = message
        registerError.isVisible = true
    }
    
    private fun navigateToMainApp() {
        val stage = loginButton.scene.window as Stage

        loginSuccessHandler?.let { handler ->
            handler(stage)
            return
        }

        // Close login window and start main app
        stage.close()

        // Start main application
        val mainApp = org.jarvis.desktop.DesktopApplication()
        val mainStage = Stage()
        mainApp.start(mainStage)
    }
}
