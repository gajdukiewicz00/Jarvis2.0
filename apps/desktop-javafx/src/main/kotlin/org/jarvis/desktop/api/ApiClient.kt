package org.jarvis.desktop.api

import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.service.AuthService
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * API client for making HTTP requests with JWT authentication.
 */
class ApiClient(
    private val configProvider: () -> ResolvedDesktopConfig = AppConfig::current,
    private val authService: AuthService? = null
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val DEFAULT_READ_TIMEOUT_MS = 10_000

        /** Explicit model profile for desktop-originated requests. */
        private const val DESKTOP_MODEL_PROFILE = "desktop-general"
    }

    private val logger = LoggerFactory.getLogger(ApiClient::class.java)

    fun get(endpoint: String): String = executeWithAuthRetry {
        executeGet(endpoint, emptyMap())
    }

    fun post(endpoint: String, body: String): String = executeWithAuthRetry {
        executePost(endpoint, body, DEFAULT_READ_TIMEOUT_MS, emptyMap())
    }

    fun post(endpoint: String, body: String, readTimeoutMs: Int): String = executeWithAuthRetry {
        executePost(endpoint, body, readTimeoutMs, emptyMap())
    }

    fun getWithHeaders(endpoint: String, headers: Map<String, String>): String = executeWithAuthRetry {
        executeGet(endpoint, headers)
    }

    fun postWithHeaders(endpoint: String, body: String, headers: Map<String, String>): String = executeWithAuthRetry {
        executePost(endpoint, body, DEFAULT_READ_TIMEOUT_MS, headers)
    }

    fun postMultipart(endpoint: String, filename: String, fileBytes: ByteArray): String = executeWithAuthRetry {
        executeMultipart(endpoint, filename, fileBytes)
    }

    fun put(endpoint: String, body: String): String = executeWithAuthRetry {
        executePut(endpoint, body, emptyMap())
    }

    fun delete(endpoint: String): String = executeWithAuthRetry {
        executeDelete(endpoint, emptyMap())
    }

    private fun executeGet(endpoint: String, extraHeaders: Map<String, String>): String {
        val baseUrl = configProvider().apiBaseUrl
        val url = URL("$baseUrl$endpoint")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Model-Profile", DESKTOP_MODEL_PROFILE)
            extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            addAuthorization(connection)
            configureTimeouts(connection)

            val responseCode = connection.responseCode
            logger.debug("GET $endpoint: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw UnauthorizedRequestException("Unauthorized: $errorBody")
            }

            return if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (isExpectedDegradedResponse(responseCode, errorBody)) {
                    logger.debug("GET $endpoint degraded: $responseCode - $errorBody")
                } else {
                    logger.warn("GET $endpoint failed: $responseCode - $errorBody")
                }
                throw httpError(responseCode, errorBody)
            }
        } catch (e: UnauthorizedRequestException) {
            throw e
        } catch (e: ConnectException) {
            logger.error("Connection refused: Server at $baseUrl is not available", e)
            throw Exception("Connection refused: Server is not available. Please check if the API gateway is running at $baseUrl")
        } catch (e: SocketTimeoutException) {
            logger.error("Connection timeout: Server at $baseUrl did not respond in time", e)
            throw Exception("Connection timeout: Server did not respond in time. Please check if the API gateway is running at $baseUrl")
        } catch (e: UnknownHostException) {
            logger.error("Unknown host: Cannot resolve host for $baseUrl", e)
            throw Exception("Unknown host: Cannot connect to server. Please check the server URL: $baseUrl")
        } catch (e: Exception) {
            if (isConnectionError(e)) {
                logger.error("Network error: ${e.message}", e)
                throw Exception("Connection error: Server is not available. Please check if the API gateway is running at $baseUrl")
            }
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun executePost(
        endpoint: String,
        body: String,
        readTimeoutMs: Int,
        extraHeaders: Map<String, String>
    ): String {
        val baseUrl = configProvider().apiBaseUrl
        val url = URL("$baseUrl$endpoint")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Model-Profile", DESKTOP_MODEL_PROFILE)
            extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            addAuthorization(connection)
            connection.doOutput = true
            configureTimeouts(connection, readTimeoutMs)

            connection.outputStream.use { os ->
                os.write(body.toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unauthorized"
                throw UnauthorizedRequestException(error)
            }

            return if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                logger.info("✅ POST request successful: $endpoint (HTTP $responseCode)")
                response
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                throw httpError(responseCode, error)
            }
        } catch (e: UnauthorizedRequestException) {
            throw e
        } catch (e: ConnectException) {
            logger.error("Connection refused: Server at $baseUrl is not available", e)
            throw Exception("Connection refused: Server is not available. Please check if the API gateway is running at $baseUrl")
        } catch (e: SocketTimeoutException) {
            logger.error("Connection timeout: Server at $baseUrl did not respond in time", e)
            throw Exception("Connection timeout: Server did not respond in time. Please check if the API gateway is running at $baseUrl")
        } catch (e: UnknownHostException) {
            logger.error("Unknown host: Cannot resolve host for $baseUrl", e)
            throw Exception("Unknown host: Cannot connect to server. Please check the server URL: $baseUrl")
        } catch (e: IOException) {
            val message = e.message ?: ""
            if (message.contains("Connection refused", ignoreCase = true) ||
                e.cause is ConnectException ||
                e.cause?.message?.contains("Connection refused", ignoreCase = true) == true) {
                logger.error("Connection refused: Server at $baseUrl is not available", e)
                throw Exception("Connection refused: Server is not available. Please check if the API gateway is running at $baseUrl")
            }
            throw e
        } catch (e: Exception) {
            if (isConnectionError(e)) {
                logger.error("Network error: ${e.message}", e)
                throw Exception("Connection error: Server is not available. Please check if the API gateway is running at $baseUrl")
            }
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun executePut(
        endpoint: String,
        body: String,
        extraHeaders: Map<String, String>
    ): String {
        val baseUrl = configProvider().apiBaseUrl
        val url = URL("$baseUrl$endpoint")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "PUT"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Model-Profile", DESKTOP_MODEL_PROFILE)
            extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            addAuthorization(connection)
            connection.doOutput = true
            configureTimeouts(connection)

            connection.outputStream.use { os ->
                os.write(body.toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unauthorized"
                throw UnauthorizedRequestException(error)
            }

            return if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                logger.info("✅ PUT request successful: $endpoint (HTTP $responseCode)")
                response
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                throw httpError(responseCode, error)
            }
        } catch (e: UnauthorizedRequestException) {
            throw e
        } catch (e: ConnectException) {
            logger.error("Connection refused: Server at $baseUrl is not available", e)
            throw Exception("Connection refused: Server is not available. Please check if the API gateway is running at $baseUrl")
        } catch (e: SocketTimeoutException) {
            logger.error("Connection timeout: Server at $baseUrl did not respond in time", e)
            throw Exception("Connection timeout: Server did not respond in time. Please check if the API gateway is running at $baseUrl")
        } catch (e: UnknownHostException) {
            logger.error("Unknown host: Cannot resolve host for $baseUrl", e)
            throw Exception("Unknown host: Cannot connect to server. Please check the server URL: $baseUrl")
        } catch (e: IOException) {
            val message = e.message ?: ""
            if (message.contains("Connection refused", ignoreCase = true) ||
                e.cause is ConnectException ||
                e.cause?.message?.contains("Connection refused", ignoreCase = true) == true) {
                logger.error("Connection refused: Server at $baseUrl is not available", e)
                throw Exception("Connection refused: Server is not available. Please check if the API gateway is running at $baseUrl")
            }
            throw e
        } catch (e: Exception) {
            if (isConnectionError(e)) {
                logger.error("Network error: ${e.message}", e)
                throw Exception("Connection error: Server is not available. Please check if the API gateway is running at $baseUrl")
            }
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun executeDelete(endpoint: String, extraHeaders: Map<String, String>): String {
        val baseUrl = configProvider().apiBaseUrl
        val url = URL("$baseUrl$endpoint")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Model-Profile", DESKTOP_MODEL_PROFILE)
            extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            addAuthorization(connection)
            configureTimeouts(connection)

            val responseCode = connection.responseCode
            logger.debug("DELETE $endpoint: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw UnauthorizedRequestException("Unauthorized: $errorBody")
            }

            return if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (isExpectedDegradedResponse(responseCode, errorBody)) {
                    logger.debug("DELETE $endpoint degraded: $responseCode - $errorBody")
                } else {
                    logger.warn("DELETE $endpoint failed: $responseCode - $errorBody")
                }
                throw httpError(responseCode, errorBody)
            }
        } catch (e: UnauthorizedRequestException) {
            throw e
        } catch (e: ConnectException) {
            logger.error("Connection refused: Server at $baseUrl is not available", e)
            throw Exception("Connection refused: Server is not available. Please check if the API gateway is running at $baseUrl")
        } catch (e: SocketTimeoutException) {
            logger.error("Connection timeout: Server at $baseUrl did not respond in time", e)
            throw Exception("Connection timeout: Server did not respond in time. Please check if the API gateway is running at $baseUrl")
        } catch (e: UnknownHostException) {
            logger.error("Unknown host: Cannot resolve host for $baseUrl", e)
            throw Exception("Unknown host: Cannot connect to server. Please check the server URL: $baseUrl")
        } catch (e: Exception) {
            if (isConnectionError(e)) {
                logger.error("Network error: ${e.message}", e)
                throw Exception("Connection error: Server is not available. Please check if the API gateway is running at $baseUrl")
            }
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun executeMultipart(endpoint: String, filename: String, fileBytes: ByteArray): String {
        val baseUrl = configProvider().apiBaseUrl
        val boundary = "----WebKitFormBoundary" + System.currentTimeMillis()
        val url = URL(baseUrl + endpoint)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("X-Model-Profile", DESKTOP_MODEL_PROFILE)
            addAuthorization(connection)
            connection.doOutput = true
            configureTimeouts(connection)

            connection.outputStream.use { os ->
                val writer = os.bufferedWriter()

                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n")
                writer.append("Content-Type: audio/wav\r\n\r\n")
                writer.flush()
                os.write(fileBytes)
                os.flush()
                writer.append("\r\n")
                writer.append("--$boundary--\r\n")
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unauthorized"
                throw UnauthorizedRequestException(error)
            }

            return if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                logger.info("✅ Multipart POST successful: $endpoint (File: $filename, HTTP $responseCode)")
                response
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                throw httpError(responseCode, error)
            }
        } catch (e: UnauthorizedRequestException) {
            throw e
        } catch (e: ConnectException) {
            logger.error("Connection refused: Server at $baseUrl is not available", e)
            throw Exception("Connection refused: Server is not available. Please check if the API gateway is running at $baseUrl")
        } catch (e: SocketTimeoutException) {
            logger.error("Connection timeout: Server at $baseUrl did not respond in time", e)
            throw Exception("Connection timeout: Server did not respond in time. Please check if the API gateway is running at $baseUrl")
        } catch (e: UnknownHostException) {
            logger.error("Unknown host: Cannot resolve host for $baseUrl", e)
            throw Exception("Unknown host: Cannot connect to server. Please check the server URL: $baseUrl")
        } catch (e: Exception) {
            if (isConnectionError(e)) {
                logger.error("Network error: ${e.message}", e)
                throw Exception("Connection error: Server is not available. Please check if the API gateway is running at $baseUrl")
            }
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun addAuthorization(connection: HttpURLConnection) {
        TokenManager.getAccessToken()?.let { token ->
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun configureTimeouts(connection: HttpURLConnection) {
        configureTimeouts(connection, DEFAULT_READ_TIMEOUT_MS)
    }

    private fun configureTimeouts(connection: HttpURLConnection, readTimeoutMs: Int) {
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
    }

    private fun httpError(code: Int, body: String): Exception = when (code) {
        HttpURLConnection.HTTP_FORBIDDEN ->
            AccessDeniedException("Access denied (403). Check user roles or service authorization.")
        HttpURLConnection.HTTP_NOT_FOUND ->
            Exception("Resource not found (404). The service may not be deployed.")
        in 500..599 ->
            Exception("Server error ($code). The backend service may be unhealthy.")
        else ->
            Exception("HTTP $code: $body")
    }

    private fun isExpectedDegradedResponse(responseCode: Int, errorBody: String): Boolean {
        if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) return true
        if (responseCode == HttpURLConnection.HTTP_UNAVAILABLE) {
            return errorBody.contains("FEATURE_DISABLED") ||
                    errorBody.contains("UNSUPPORTED_RUNTIME_MODE")
        }
        return false
    }

    private fun isConnectionError(exception: Exception): Boolean {
        val message = exception.message ?: ""
        return message.contains("Connection refused", ignoreCase = true) ||
                message.contains("not available", ignoreCase = true) ||
                message.contains("Connection error", ignoreCase = true) ||
                exception.cause is ConnectException ||
                exception.cause?.message?.contains("Connection refused", ignoreCase = true) == true
    }

    private fun <T> executeWithAuthRetry(operation: () -> T): T {
        var hasRetried = false
        while (true) {
            try {
                return operation()
            } catch (e: UnauthorizedRequestException) {
                if (hasRetried || !attemptTokenRefresh()) {
                    throw Exception("Session expired. Please login again.", e)
                }
                hasRetried = true
            }
        }
    }

    private fun attemptTokenRefresh(): Boolean {
        val refreshToken = TokenManager.getRefreshToken()
        val service = authService

        if (refreshToken.isNullOrBlank() || service == null) {
            logger.warn("Cannot refresh token: refresh token or AuthService missing")
            TokenManager.clearTokens()
            return false
        }

        return try {
            logger.info("Refreshing JWT token...")
            val authResponse = service.refreshTokens(refreshToken)
            TokenManager.saveTokens(
                authResponse.accessToken,
                authResponse.refreshToken,
                authResponse.username,
                authResponse.role
            )
            true
        } catch (ex: Exception) {
            logger.error("Token refresh failed: ${ex.message}", ex)
            TokenManager.clearTokens()
            false
        }
    }
}

private class UnauthorizedRequestException(message: String) : Exception(message)
class AccessDeniedException(message: String) : Exception(message)
