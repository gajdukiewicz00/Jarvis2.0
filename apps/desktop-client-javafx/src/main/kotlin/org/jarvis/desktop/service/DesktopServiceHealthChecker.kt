package org.jarvis.desktop.service

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.jarvis.desktop.api.AccessDeniedException
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DesktopServiceHealthChecker(
    private val apiClient: ApiClient = ApiClient(),
    private val configProvider: () -> ResolvedDesktopConfig = AppConfig::current,
    private val apiProbe: (String, String) -> ProbeOutcome = { _, endpoint -> defaultApiProbe(apiClient, endpoint) },
    private val gatewayProbe: (URI) -> ProbeOutcome = ::defaultGatewayProbe,
    private val webSocketProbe: (String, Map<String, String>) -> ProbeOutcome = ::defaultWebSocketProbe
) {
    enum class Status {
        ONLINE,
        OFFLINE,
        UNAUTHORIZED
    }

    data class ServiceCheck(
        val name: String,
        val status: Status,
        val target: String,
        val detail: String
    )

    data class ProbeOutcome(
        val status: Status,
        val detail: String
    )

    fun resolvedConfig(): ResolvedDesktopConfig = configProvider()

    fun checkAll(): List<ServiceCheck> {
        val config = resolvedConfig()
        val wsHeaders = webSocketHeaders()

        return listOf(
            checkGateway(config),
            checkApi("Auth Context", "/security/auth/me", "${config.apiBaseUrl}/security/auth/me"),
            checkApi("Smart Home API", "/smarthome/devices", "${config.apiBaseUrl}/smarthome/devices"),
            checkApi("Life Tracker API", "/life/finance/expenses", "${config.apiBaseUrl}/life/finance/expenses"),
            checkApi("Analytics API", "/analytics/expenses/by-month", "${config.apiBaseUrl}/analytics/expenses/by-month"),
            checkWebSocket("Voice WS", config.voiceWebSocketUrl, wsHeaders),
            checkWebSocket("PC Control WS", config.pcControlWebSocketUrl, wsHeaders)
        )
    }

    internal fun webSocketHeaders(): Map<String, String> {
        return buildMap {
            TokenManager.getAccessToken()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
            TokenManager.getUserId()?.takeIf { it.isNotBlank() }?.let { put("X-User-Id", it) }
            TokenManager.getUsername()?.takeIf { it.isNotBlank() }?.let { put("X-Username", it) }
            TokenManager.getUserRole()?.takeIf { it.isNotBlank() }?.let { put("X-User-Roles", it) }
        }
    }

    private fun checkGateway(config: ResolvedDesktopConfig): ServiceCheck {
        val target = "${config.apiGatewayBaseUrl}/actuator/health/readiness"
        val outcome = gatewayProbe(URI.create(target))
        return ServiceCheck("API Gateway", outcome.status, target, outcome.detail)
    }

    private fun checkApi(name: String, endpoint: String, target: String): ServiceCheck {
        val outcome = apiProbe(name, endpoint)
        return ServiceCheck(name, outcome.status, target, outcome.detail)
    }

    private fun checkWebSocket(name: String, target: String, headers: Map<String, String>): ServiceCheck {
        val outcome = webSocketProbe(target, headers)
        return ServiceCheck(name, outcome.status, target, outcome.detail)
    }

    companion object {
        private fun defaultApiProbe(apiClient: ApiClient, endpoint: String): ProbeOutcome {
            return try {
                apiClient.get(endpoint)
                ProbeOutcome(Status.ONLINE, "OK")
            } catch (e: Exception) {
                ProbeOutcome(
                    when (e) {
                        is AccessDeniedException -> Status.UNAUTHORIZED
                        else -> {
                            val message = e.message ?: ""
                            if (message.contains("session expired", ignoreCase = true) ||
                                message.contains("unauthorized", ignoreCase = true)) {
                                Status.UNAUTHORIZED
                            } else {
                                Status.OFFLINE
                            }
                        }
                    },
                    e.message ?: "Request failed"
                )
            }
        }

        private fun defaultGatewayProbe(uri: URI): ProbeOutcome {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
            return try {
                val response = client.send(
                    HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                )

                if (response.statusCode() in 200..299 && response.body().contains("\"UP\"", ignoreCase = true)) {
                    ProbeOutcome(Status.ONLINE, "UP")
                } else if (response.statusCode() in 200..299) {
                    ProbeOutcome(Status.OFFLINE, "Unexpected response body")
                } else {
                    ProbeOutcome(Status.OFFLINE, "HTTP ${response.statusCode()}")
                }
            } catch (e: Exception) {
                ProbeOutcome(Status.OFFLINE, e.message ?: "Health check failed")
            }
        }

        private fun defaultWebSocketProbe(url: String, headers: Map<String, String>): ProbeOutcome {
            val latch = CountDownLatch(1)
            val outcome = java.util.concurrent.atomic.AtomicReference(ProbeOutcome(Status.OFFLINE, "Handshake timed out"))
            val client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> addHeader(name, value) }
            }.build()

            val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    outcome.set(ProbeOutcome(Status.ONLINE, "Connected"))
                    webSocket.close(1000, "Health check complete")
                    latch.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val formatted = TransportErrorFormatter.describeFailure("WebSocket probe", url, t, response)
                    outcome.set(
                        when (response?.code) {
                            401, 403 -> ProbeOutcome(Status.UNAUTHORIZED, formatted.diagnosticMessage)
                            else -> ProbeOutcome(Status.OFFLINE, formatted.diagnosticMessage)
                        }
                    )
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    latch.countDown()
                }
            })

            latch.await(3, TimeUnit.SECONDS)
            webSocket.cancel()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            return outcome.get()
        }
    }
}
