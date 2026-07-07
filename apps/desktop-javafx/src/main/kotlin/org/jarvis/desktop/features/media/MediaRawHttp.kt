package org.jarvis.desktop.features.media

import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Minimal HTTP helper for the two media-service interactions that the shared
 * [org.jarvis.desktop.api.ApiClient] cannot express:
 *
 *  - Job-creation endpoints (`POST /media/jobs/{action}`) reply `202 Accepted`
 *    once the job is scheduled (see `MediaPipelineController`). `ApiClient.post`
 *    only treats HTTP 200/201 as success, so routing job creation through it
 *    would misreport every successful submission as a failure.
 *  - Artifact download (`GET /media/jobs/{id}/artifacts/{index}`) returns
 *    arbitrary binary bytes (audio/video/subtitle files). `ApiClient.get`
 *    decodes the response body as a `String`, which corrupts non-text bytes.
 *
 * This deliberately does not attempt `ApiClient`'s 401 refresh-and-retry
 * dance — on a stale token it surfaces a clear "session may have expired"
 * message instead so the caller can prompt the user, rather than silently
 * duplicating that auth-refresh logic here.
 */
class MediaRawHttp(
    private val baseUrlProvider: () -> String = { AppConfig.apiBaseUrl }
) {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val DESKTOP_MODEL_PROFILE = "desktop-general"
    }

    /** Thrown for any non-2xx response, carrying the HTTP status code. */
    class MediaHttpException(val statusCode: Int, message: String) : Exception(message)

    /** POSTs a JSON body and accepts any 2xx status (including 202) as success. */
    fun postJson(endpoint: String, jsonBody: String): String {
        val connection = openConnection(endpoint)
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Model-Profile", DESKTOP_MODEL_PROFILE)
            addAuthorization(connection)
            connection.doOutput = true
            configureTimeouts(connection)

            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            readResponseText(connection)
        } catch (e: ConnectException) {
            throw connectionRefused(e)
        } catch (e: SocketTimeoutException) {
            throw connectionTimeout(e)
        } catch (e: UnknownHostException) {
            throw unknownHost(e)
        } finally {
            connection.disconnect()
        }
    }

    /** GETs the response body as raw bytes — no charset decoding, safe for binary artifacts. */
    fun getBytes(endpoint: String): ByteArray {
        val connection = openConnection(endpoint)
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("X-Model-Profile", DESKTOP_MODEL_PROFILE)
            addAuthorization(connection)
            configureTimeouts(connection)

            val code = connection.responseCode
            if (code in 200..299) {
                connection.inputStream.use { it.readBytes() }
            } else {
                throw httpError(code, connection)
            }
        } catch (e: ConnectException) {
            throw connectionRefused(e)
        } catch (e: SocketTimeoutException) {
            throw connectionTimeout(e)
        } catch (e: UnknownHostException) {
            throw unknownHost(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponseText(connection: HttpURLConnection): String {
        val code = connection.responseCode
        return if (code in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            throw httpError(code, connection)
        }
    }

    private fun httpError(code: Int, connection: HttpURLConnection): MediaHttpException {
        val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
        val message = if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            "Session may have expired (401). Please log in again."
        } else {
            "HTTP $code: $errorBody"
        }
        return MediaHttpException(code, message)
    }

    private fun openConnection(endpoint: String): HttpURLConnection {
        val url = URL("${baseUrlProvider()}$endpoint")
        return url.openConnection() as HttpURLConnection
    }

    private fun addAuthorization(connection: HttpURLConnection) {
        TokenManager.getAccessToken()?.let { token ->
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun configureTimeouts(connection: HttpURLConnection) {
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
    }

    private fun connectionRefused(cause: Exception) =
        MediaHttpException(-1, "Connection refused: media-service is not reachable at ${baseUrlProvider()}")
            .apply { initCause(cause) }

    private fun connectionTimeout(cause: Exception) =
        MediaHttpException(-1, "Connection timeout: media-service did not respond in time").apply { initCause(cause) }

    private fun unknownHost(cause: Exception) =
        MediaHttpException(-1, "Unknown host: cannot resolve media-service address").apply { initCause(cause) }
}
