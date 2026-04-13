package org.jarvis.desktop.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import org.jarvis.desktop.auth.AuthResponse
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.auth.RefreshRequest

/**
 * Service for JWT authentication and token management.
 */
class AuthService(
    private val configProvider: () -> ResolvedDesktopConfig = AppConfig::current
) {

    private var currentToken: String? = null
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /**
     * Deprecated single-argument login helper from the legacy token-generation flow.
     * The current auth authority requires username + password via /auth/login.
     */
    @Deprecated("Use /auth/login with username and password")
    fun login(username: String): String {
        throw UnsupportedOperationException(
            "Single-argument login is unsupported. Use /auth/login with username and password."
        )
    }

    fun refreshTokens(refreshToken: String): AuthResponse {
        val requestBody = objectMapper.writeValueAsString(RefreshRequest(refreshToken))
        val url = java.net.URL("${configProvider().apiGatewayBaseUrl}/auth/refresh")
        val connection = url.openConnection() as java.net.HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(requestBody.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val authResponse = objectMapper.readValue(response, AuthResponse::class.java)
                currentToken = authResponse.accessToken
                return authResponse
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                throw Exception("Failed to refresh token: HTTP $responseCode - $error")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Get current JWT token (null if not logged in).
     */
    fun getToken(): String? = currentToken

    /**
     * Check if user is logged in.
     */
    fun isLoggedIn(): Boolean = currentToken != null

    /**
     * Logout and clear persisted local tokens.
     */
    fun logout() {
        val refreshToken = TokenManager.getRefreshToken()
        if (!refreshToken.isNullOrBlank()) {
            val requestBody = objectMapper.writeValueAsString(RefreshRequest(refreshToken))
            val url = java.net.URL("${configProvider().apiGatewayBaseUrl}/auth/logout")
            val connection = url.openConnection() as java.net.HttpURLConnection

            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(requestBody.toByteArray()) }
                connection.responseCode
            } catch (_: Exception) {
                // Local token cleanup still proceeds even if the server-side revoke call fails.
            } finally {
                connection.disconnect()
            }
        }
        currentToken = null
        TokenManager.clearTokens()
    }
}
