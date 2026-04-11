package org.jarvis.desktop.config

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal enum class RuntimeEndpointMode(val description: String) {
    LOCAL("local"),
    K8S("k8s")
}

internal data class LocalRuntimeEndpointSnapshot(
    val apiGatewayBaseUrl: String,
    val reason: String,
    val runtimeMode: RuntimeEndpointMode = RuntimeEndpointMode.LOCAL
) {
    val configSource: ConfigSource
        get() = when (runtimeMode) {
            RuntimeEndpointMode.LOCAL -> ConfigSource.ACTIVE_LOCAL_RUNTIME
            RuntimeEndpointMode.K8S -> ConfigSource.ACTIVE_K8S_RUNTIME
        }
}

internal class LocalRuntimeEndpointDetector(
    private val summaryPath: Path = Paths.get(System.getProperty("user.home"), ".jarvis", "run", "last-run.json"),
    private val healthProbe: (URI) -> Boolean = defaultHealthProbe(),
    private val clock: Clock = Clock.systemUTC()
) {

    fun summaryFingerprint(): String? {
        if (!Files.isRegularFile(summaryPath)) {
            return null
        }

        val lastModifiedMillis = runCatching { Files.getLastModifiedTime(summaryPath).toMillis() }.getOrNull()
            ?: return summaryPath.toAbsolutePath().toString()
        return "${summaryPath.toAbsolutePath()}:$lastModifiedMillis"
    }

    fun detectActive(): LocalRuntimeEndpointSnapshot? {
        if (!Files.isRegularFile(summaryPath)) {
            return null
        }

        val summary = runCatching { Files.readString(summaryPath) }.getOrNull() ?: return null
        val runtimeMode = when (extractJsonString(summary, "runtimeMode")?.trim()?.lowercase()) {
            "local" -> RuntimeEndpointMode.LOCAL
            "k8s" -> RuntimeEndpointMode.K8S
            else -> null
        } ?: return null

        val status = extractJsonString(summary, "status")?.trim()?.lowercase() ?: return null
        if (status == "stopped") {
            return null
        }

        val apiGatewayBaseUrl = DesktopConfigResolver.normalizeBaseUrl(extractJsonString(summary, "apiUrl"))
            ?: return null
        if (!isReady(apiGatewayBaseUrl)) {
            return null
        }

        val timestamp = extractJsonString(summary, "timestamp")
        val ageSuffix = timestamp
            ?.let { runCatching { Duration.between(Instant.parse(it), clock.instant()).abs() }.getOrNull() }
            ?.let { ", age=${formatAge(it)}" }
            ?: ""

        return LocalRuntimeEndpointSnapshot(
            apiGatewayBaseUrl = apiGatewayBaseUrl,
            reason = "Active ${runtimeMode.description} runtime detected from ${summaryPath.toAbsolutePath()} (status=$status$ageSuffix, actuator readiness probe OK)",
            runtimeMode = runtimeMode
        )
    }

    fun isReady(baseUrl: String): Boolean {
        val normalizedBaseUrl = DesktopConfigResolver.normalizeBaseUrl(baseUrl) ?: return false
        val healthUri = URI.create("${normalizedBaseUrl.trimEnd('/')}/actuator/health/readiness")
        return runCatching { healthProbe(healthUri) }.getOrDefault(false)
    }

    fun isReachable(baseUrl: String): Boolean {
        val normalizedBaseUrl = DesktopConfigResolver.normalizeBaseUrl(baseUrl) ?: return false
        val healthUri = URI.create("${normalizedBaseUrl.trimEnd('/')}/actuator/health/readiness")
        return runCatching { defaultReachabilityProbe()(healthUri) }.getOrDefault(false)
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    private fun formatAge(duration: Duration): String {
        return when {
            duration.toMinutes() < 1 -> "${duration.seconds}s"
            duration.toHours() < 1 -> "${duration.toMinutes()}m"
            else -> "${duration.toHours()}h${duration.toMinutesPart()}m"
        }
    }

    companion object {
        private fun defaultHealthProbe(): (URI) -> Boolean {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build()
            return { uri ->
                val response = client.send(
                    HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(1))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                response.statusCode() in 200..299 && response.body().contains("\"UP\"", ignoreCase = true)
            }
        }

        private fun defaultReachabilityProbe(): (URI) -> Boolean {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build()
            return { uri ->
                client.send(
                    HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(1))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding()
                )
                true
            }
        }
    }
}
