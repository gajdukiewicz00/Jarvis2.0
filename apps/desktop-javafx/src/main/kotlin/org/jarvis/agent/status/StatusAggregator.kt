package org.jarvis.agent.status

import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Phase 6 — polls the backend health surfaces and surfaces a
 * service → status map for the desktop panel.
 *
 * <p>Pass 1 polls actuator endpoints synchronously every {@code pollMillis}
 * (default 15s) over plain HTTP through the api-gateway. Phase 11 will
 * subscribe to a Kafka events topic for push updates so the panel stops
 * lagging behind reality.</p>
 */
class StatusAggregator(
    private val backendBaseUrl: String,
    private val pollMillis: Long = 15_000,
    httpTimeout: Duration = Duration.ofSeconds(3)
) {
    private val log = LoggerFactory.getLogger(StatusAggregator::class.java)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(httpTimeout)
        .readTimeout(httpTimeout)
        .build()

    private val statuses = ConcurrentHashMap<String, ServiceStatus>()
    private var poller: Thread? = null
    @Volatile private var running = false

    /** key: service name, value: latest known status. */
    val snapshot: Map<String, ServiceStatus>
        get() = statuses.toMap()

    fun start() {
        if (running) return
        running = true
        poller = Thread({
            while (running) {
                refresh()
                try {
                    Thread.sleep(pollMillis)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "jarvis-agent-status-poller").apply {
            isDaemon = true
            start()
        }
        log.info("StatusAggregator started, polling {} every {}ms", backendBaseUrl, pollMillis)
    }

    fun stop() {
        running = false
        poller?.interrupt()
        poller = null
    }

    fun refresh(): Map<String, ServiceStatus> {
        for ((name, path) in TARGETS) {
            statuses[name] = probe(name, path)
        }
        return snapshot
    }

    private fun probe(name: String, path: String): ServiceStatus {
        val url = backendBaseUrl.trimEnd('/') + path
        val req = Request.Builder().url(url).get().build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    ServiceStatus(name, ProbeStatus.DEGRADED, "HTTP ${resp.code}")
                } else {
                    ServiceStatus(name, ProbeStatus.UP, null)
                }
            }
        }.getOrElse { ex ->
            ServiceStatus(name, ProbeStatus.DOWN, "${ex.javaClass.simpleName}: ${ex.message}")
        }
    }

    data class ServiceStatus(val name: String, val status: ProbeStatus, val detail: String?)
    enum class ProbeStatus { UP, DEGRADED, DOWN }

    companion object {
        private val TARGETS = linkedMapOf(
            "backend-api-gateway" to "/actuator/health",
            "voice-gateway" to "/api/v1/voice/runtime",
            "llm-service" to "/api/v1/llm/health",
            "memory-service" to "/api/v1/memory/health",
            "vision-security" to "/api/v1/vision/health"
        )
    }
}
