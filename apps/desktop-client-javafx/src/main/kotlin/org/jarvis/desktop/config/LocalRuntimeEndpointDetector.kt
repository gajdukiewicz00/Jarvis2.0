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

internal data class LocalRuntimeEndpointSnapshot(
    val apiGatewayBaseUrl: String,
    val reason: String
)

internal class LocalRuntimeEndpointDetector(
    private val summaryPath: Path = Paths.get(System.getProperty("user.home"), ".jarvis", "run", "last-run.json"),
    private val healthProbe: (URI) -> Boolean = defaultHealthProbe(),
    private val clock: Clock = Clock.systemUTC()
) {

    fun detectActive(): LocalRuntimeEndpointSnapshot? {
        if (!Files.isRegularFile(summaryPath)) {
            return null
        }

        val summary = runCatching { Files.readString(summaryPath) }.getOrNull() ?: return null
        val runtimeMode = extractJsonString(summary, "runtimeMode")?.trim()?.lowercase() ?: "local"
        if (runtimeMode != "local") {
            return null
        }

        val status = extractJsonString(summary, "status")?.trim()?.lowercase() ?: return null
        if (status == "stopped") {
            return null
        }

        val apiGatewayBaseUrl = DesktopConfigResolver.normalizeBaseUrl(extractJsonString(summary, "apiUrl"))
            ?: return null
        val healthUri = URI.create("${apiGatewayBaseUrl.trimEnd('/')}/actuator/health")
        val reachable = runCatching { healthProbe(healthUri) }.getOrDefault(false)
        if (!reachable) {
            return null
        }

        val timestamp = extractJsonString(summary, "timestamp")
        val ageSuffix = timestamp
            ?.let { runCatching { Duration.between(Instant.parse(it), clock.instant()).abs() }.getOrNull() }
            ?.let { ", age=${formatAge(it)}" }
            ?: ""

        return LocalRuntimeEndpointSnapshot(
            apiGatewayBaseUrl = apiGatewayBaseUrl,
            reason = "Active local runtime detected from ${summaryPath.toAbsolutePath()} (status=$status$ageSuffix, actuator health probe OK)"
        )
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
    }
}
