package org.jarvis.desktop.runtime

import org.jarvis.launcher.JarvisPaths
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration

class LocalRuntimeHealthProbe(
    private val apiGatewayBaseUrlProvider: () -> String,
    private val runtimeModeProvider: () -> String = { JarvisPaths.getRuntimeMode() },
    private val fetcher: (URI) -> HttpResult = defaultFetcher(),
    private val clock: Clock = Clock.systemUTC()
) {

    data class HttpResult(val statusCode: Int, val body: String)

    fun probe(): DesktopRuntimeMonitor.ConnectionStatus {
        val apiGatewayBaseUrl = apiGatewayBaseUrlProvider()
        val isK8sMode = runtimeModeProvider().equals(K8S_RUNTIME_MODE, ignoreCase = true)
        return try {
            val readinessUri = URI.create("${apiGatewayBaseUrl.trimEnd('/')}/actuator/health/readiness")
            val readinessResponse = fetcher(readinessUri)
            when {
                readinessResponse.statusCode in 200..299 && readinessResponse.body.contains("\"UP\"", ignoreCase = true) ->
                    status(DesktopRuntimeMonitor.ConnectionState.CONNECTED, "Local runtime healthy at $readinessUri")
                readinessResponse.statusCode == 404 || readinessResponse.statusCode == 405 ->
                    probeLegacyHealth(apiGatewayBaseUrl, isK8sMode)
                isProtectedResponse(readinessResponse.statusCode) ->
                    protectedStatus(readinessUri.toString(), readinessResponse.statusCode, isK8sMode)
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

    private fun probeLegacyHealth(apiGatewayBaseUrl: String, isK8sMode: Boolean): DesktopRuntimeMonitor.ConnectionStatus {
        val legacyUri = URI.create("${apiGatewayBaseUrl.trimEnd('/')}/actuator/health")
        val response = fetcher(legacyUri)
        return when {
            response.statusCode in 200..299 && response.body.contains("\"UP\"", ignoreCase = true) ->
                status(DesktopRuntimeMonitor.ConnectionState.CONNECTED, "Local runtime healthy at $legacyUri")
            isProtectedResponse(response.statusCode) ->
                protectedStatus(legacyUri.toString(), response.statusCode, isK8sMode)
            response.statusCode in 200..299 ->
                status(DesktopRuntimeMonitor.ConnectionState.DEGRADED, "Runtime reachable but not healthy: ${response.body.take(120)}")
            else ->
                status(DesktopRuntimeMonitor.ConnectionState.ERROR, "Runtime health check failed with HTTP ${response.statusCode}")
        }
    }

    private fun isProtectedResponse(statusCode: Int): Boolean = statusCode == 401 || statusCode == 403

    /**
     * A 401/403 from the gateway's health endpoint means the gateway process is up and
     * answering — it is just gated behind auth. In k8s runtime mode there is no local
     * backend PID and no kubectl dependency for this check, so a protected-but-reachable
     * gateway must be reported CONNECTED, not DEGRADED/ERROR. Outside k8s mode we keep a
     * more cautious DEGRADED (reachable, but not confirmed healthy) since an unauthenticated
     * actuator endpoint is unexpected for a local dev runtime.
     */
    private fun protectedStatus(uri: String, statusCode: Int, isK8sMode: Boolean): DesktopRuntimeMonitor.ConnectionStatus {
        return if (isK8sMode) {
            status(
                DesktopRuntimeMonitor.ConnectionState.CONNECTED,
                "API gateway reachable at $uri (HTTP $statusCode, protected endpoint) — " +
                    "backend PID and kubectl checks do not apply in k8s runtime mode"
            )
        } else {
            status(
                DesktopRuntimeMonitor.ConnectionState.DEGRADED,
                "API gateway reachable at $uri but returned HTTP $statusCode (protected endpoint, not confirmed healthy)"
            )
        }
    }

    companion object {
        private const val K8S_RUNTIME_MODE = "k8s"

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
