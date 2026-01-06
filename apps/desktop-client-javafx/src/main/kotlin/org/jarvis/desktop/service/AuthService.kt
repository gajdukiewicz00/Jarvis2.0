package org.jarvis.desktop.service

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jarvis.desktop.auth.AuthResponse
import org.jarvis.desktop.auth.RefreshRequest
import org.jarvis.desktop.model.TokenRequest
import org.jarvis.desktop.model.TokenResponse

/**
 * Service for JWT authentication and token management.
 */
class AuthService(private val baseUrl: String) {

    private var currentToken: String? = null
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Login and get JWT token.
     */
    fun login(username: String): String {
        val request = TokenRequest(username = username)
        val requestBody = json.encodeToString(request)

        val url = java.net.URL("$baseUrl/api/v1/security/auth/generate")
        val connection = url.openConnection() as java.net.HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val tokenResponse = json.decodeFromString<TokenResponse>(response)
                currentToken = tokenResponse.token
                return tokenResponse.token
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                throw Exception("Failed to login: HTTP $responseCode - $error")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun refreshTokens(refreshToken: String): AuthResponse {
        val requestBody = json.encodeToString(RefreshRequest(refreshToken))
        val url = java.net.URL("$baseUrl/auth/refresh")
        val connection = url.openConnection() as java.net.HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(requestBody.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val authResponse = json.decodeFromString<AuthResponse>(response)
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
     * Logout (clear token).
     */
    fun logout() {
        currentToken = null
    }
}
