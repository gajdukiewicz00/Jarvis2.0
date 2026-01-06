package org.jarvis.desktop.api

import org.jarvis.desktop.auth.TokenManager
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
    private val baseUrl: String,
    private val authService: AuthService? = null
) {
    private val logger = LoggerFactory.getLogger(ApiClient::class.java)

    fun get(endpoint: String): String = executeWithAuthRetry {
        executeGet(endpoint)
    }

    fun post(endpoint: String, body: String): String = executeWithAuthRetry {
        executePost(endpoint, body)
    }

    fun postMultipart(endpoint: String, filename: String, fileBytes: ByteArray): String = executeWithAuthRetry {
        executeMultipart(endpoint, filename, fileBytes)
    }

    private fun executeGet(endpoint: String): String {
        val url = URL("$baseUrl$endpoint")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
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
                logger.warn("GET $endpoint failed: $responseCode - $errorBody")
                throw Exception("Request failed with code $responseCode")
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

    private fun executePost(endpoint: String, body: String): String {
        val url = URL("$baseUrl$endpoint")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
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

            return if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                logger.info("✅ POST request successful: $endpoint (HTTP $responseCode)")
                response
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                throw Exception("HTTP $responseCode: $error")
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

    private fun executeMultipart(endpoint: String, filename: String, fileBytes: ByteArray): String {
        val boundary = "----WebKitFormBoundary" + System.currentTimeMillis()
        val url = URL(baseUrl + endpoint)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
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
                throw Exception("HTTP $responseCode: $error")
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
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
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
