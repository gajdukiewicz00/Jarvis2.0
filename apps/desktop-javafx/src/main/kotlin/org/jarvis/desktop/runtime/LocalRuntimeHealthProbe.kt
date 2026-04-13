package org.jarvis.desktop.runtime

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration

class LocalRuntimeHealthProbe(
    private val apiGatewayBaseUrlProvider: () -> String,
    private val fetcher: (URI) -> HttpResult = defaultFetcher(),
    private val clock: Clock = Clock.systemUTC()
) {

    data class HttpResult(val statusCode: Int, val body: String)

    fun probe(): DesktopRuntimeMonitor.ConnectionStatus {
        val apiGatewayBaseUrl = apiGatewayBaseUrlProvider()
        return try {
            val readinessUri = URI.create("${apiGatewayBaseUrl.trimEnd('/')}/actuator/health/readiness")
            val readinessResponse = fetcher(readinessUri)
            when {
                readinessResponse.statusCode in 200..299 && readinessResponse.body.contains("\"UP\"", ignoreCase = true) ->
                    status(DesktopRuntimeMonitor.ConnectionState.CONNECTED, "Local runtime healthy at $readinessUri")
                readinessResponse.statusCode == 404 || readinessResponse.statusCode == 405 ->
                    probeLegacyHealth(apiGatewayBaseUrl)
                readinessResponse.statusCode in 200..299 ->
                    status(
                        DesktopRuntimeMonitor.ConnectionState.DEGRADED,
                        "Runtime reachable but not healthy: ${readinessResponse.body.take(120)}"
                    )
                else ->
                    status(
                        DesktopRuntimeMonitor.ConnectionState.ERROR,
                        "Runtime health check failed with HTTP ${readinessResponse.statusCode}"
                    )
            }
        } catch (e: Exception) {
            status(DesktopRuntimeMonitor.ConnectionState.ERROR, "Runtime unreachable: ${e.message}")
        }
    }

    private fun status(
        state: DesktopRuntimeMonitor.ConnectionState,
        detail: String
    ): DesktopRuntimeMonitor.ConnectionStatus {
        return DesktopRuntimeMonitor.ConnectionStatus(state, detail, clock.instant())
    }

    private fun probeLegacyHealth(apiGatewayBaseUrl: String): DesktopRuntimeMonitor.ConnectionStatus {
        val legacyUri = URI.create("${apiGatewayBaseUrl.trimEnd('/')}/actuator/health")
        val response = fetcher(legacyUri)
        return when {
            response.statusCode in 200..299 && response.body.contains("\"UP\"", ignoreCase = true) ->
                status(DesktopRuntimeMonitor.ConnectionState.CONNECTED, "Local runtime healthy at $legacyUri")
            response.statusCode in 200..299 ->
                status(DesktopRuntimeMonitor.ConnectionState.DEGRADED, "Runtime reachable but not healthy: ${response.body.take(120)}")
            else ->
                status(DesktopRuntimeMonitor.ConnectionState.ERROR, "Runtime health check failed with HTTP ${response.statusCode}")
        }
    }

    companion object {
        private fun defaultFetcher(): (URI) -> HttpResult {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
            return { uri ->
                val response = client.send(
                    HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                HttpResult(response.statusCode(), response.body())
            }
        }
    }
}
