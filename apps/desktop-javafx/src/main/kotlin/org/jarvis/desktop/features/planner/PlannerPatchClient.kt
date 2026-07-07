package org.jarvis.desktop.features.planner

import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal PATCH support for the recurring-task occurrence endpoints
 * (`PATCH /api/v1/planner/tasks/{id}/skip-occurrence` and
 * `.../complete-occurrence`).
 *
 * `ApiClient` (shared infra, out of scope for this feature slice) only
 * exposes GET/POST/PUT/DELETE via `HttpURLConnection`, which rejects the
 * "PATCH" method outright. Rather than widening that shared surface, this
 * wraps the standard `java.net.http.HttpClient` (JDK 11+), which supports
 * arbitrary HTTP methods, and mirrors `ApiClient`'s constructor-injected
 * provider pattern so it can be pointed at a fake server in tests instead of
 * the real desktop config/token singletons.
 */
class PlannerPatchClient(
    private val baseUrlProvider: () -> String = { AppConfig.current().apiBaseUrl },
    private val tokenProvider: () -> String? = TokenManager::getAccessToken
) {
    companion object {
        private const val TIMEOUT_SECONDS = 10L
        private const val DESKTOP_MODEL_PROFILE = "desktop-general"
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    /** Sends a bodiless PATCH to `endpoint` (relative to the API base URL) and returns the response body. */
    fun patch(endpoint: String): String {
        val requestBuilder = HttpRequest.newBuilder(URI.create("${baseUrlProvider()}$endpoint"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Accept", "application/json")
            .header("X-Model-Profile", DESKTOP_MODEL_PROFILE)
            .method("PATCH", HttpRequest.BodyPublishers.noBody())
        tokenProvider()?.let { token -> requestBuilder.header("Authorization", "Bearer $token") }

        val response = try {
            httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw Exception("Connection error: ${e.message ?: "Server is not available"}", e)
        }

        if (response.statusCode() !in 200..299) {
            throw Exception("HTTP ${response.statusCode()}: ${response.body()}")
        }
        return response.body()
    }
}
