package org.jarvis.agent.heartbeat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.agent.killswitch.KillSwitchManager
import org.jarvis.commands.agent.AgentCapability
import org.jarvis.commands.agent.AgentHeartbeat
import org.jarvis.commands.agent.AgentIdentity
import org.jarvis.commands.agent.AgentStatus
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 6 — periodic POST of the agent's current snapshot to the backend's
 * agent-control endpoint.
 *
 * <p>Keeps the desktop independent of Spring: plain OkHttp + a single
 * scheduler thread. Failures are logged but don't crash the agent — the
 * backend marks agents OFFLINE based on staleness, which is the right
 * fallback when the network flaps.</p>
 */
class HeartbeatPublisher(
    private val identity: AgentIdentity,
    private val capabilities: Set<AgentCapability>,
    private val killSwitch: KillSwitchManager,
    private val feed: AgentLiveFeed,
    private val backendBaseUrl: String,
    private val intervalSeconds: Long = 15,
    private val mapper: ObjectMapper = defaultMapper()
) : Closeable {

    private val log = LoggerFactory.getLogger(HeartbeatPublisher::class.java)
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(5))
        .build()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jarvis-agent-heartbeat").also { it.isDaemon = true }
    }
    private val statusOverride = AtomicReference<AgentStatus?>(null)

    fun start() {
        executor.scheduleAtFixedRate(
            { runCatching { publishOnce() }.onFailure { log.warn("heartbeat tick failed: ${it.message}") } },
            0, intervalSeconds, TimeUnit.SECONDS
        )
        log.info("HeartbeatPublisher started: every {}s -> {}", intervalSeconds, urlFor("/api/v1/agent/heartbeat"))
    }

    /** Override the published status (e.g. mark BOOTING during init). Pass null to clear. */
    fun overrideStatus(status: AgentStatus?) {
        statusOverride.set(status)
    }

    fun publishOnce(): Boolean {
        val ks = killSwitch.current()
        val effective = statusOverride.get() ?: when {
            ks.isEngaged -> AgentStatus.KILL_SWITCH
            else -> AgentStatus.READY
        }
        val heartbeat = AgentHeartbeat.builder()
            .identity(identity)
            .status(effective)
            .capabilities(capabilities)
            .killSwitch(ks)
            .sentAt(Instant.now())
            .metadata(mapOf(
                "feedSize" to feed.size(),
                "feedEmitted" to feed.emittedCount(),
                "feedDropped" to feed.droppedCount()
            ))
            .build()

        val body = mapper.writeValueAsString(heartbeat)
        val request = Request.Builder()
            .url(urlFor("/api/v1/agent/heartbeat"))
            .header("Content-Type", "application/json")
            .header("X-Agent-Id", identity.agentId ?: "")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log.warn("heartbeat HTTP {} for agent={} status={}",
                        resp.code, identity.agentId, effective)
                    false
                } else {
                    log.debug("heartbeat OK for agent={} status={}", identity.agentId, effective)
                    true
                }
            }
        }.getOrElse { ex ->
            log.warn("heartbeat post failed for agent={}: {}", identity.agentId, ex.message)
            false
        }
    }

    fun register(): Boolean {
        val identityJson = mapper.writeValueAsString(identity)
        val request = Request.Builder()
            .url(urlFor("/api/v1/agent/register"))
            .header("Content-Type", "application/json")
            .post(identityJson.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                val ok = resp.isSuccessful
                log.info("agent register: HTTP {} for agent={}", resp.code, identity.agentId)
                ok
            }
        }.getOrElse {
            log.warn("agent register failed for agent={}: {}", identity.agentId, it.message)
            false
        }
    }

    private fun urlFor(path: String): String = backendBaseUrl.trimEnd('/') + path

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        fun defaultMapper(): ObjectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}
