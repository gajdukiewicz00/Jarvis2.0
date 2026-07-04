package org.jarvis.agent.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.commands.agent.AgentEvent
import org.jarvis.events.AuditEventType
import org.jarvis.events.EventCategory
import org.jarvis.events.EventSeverity
import org.jarvis.events.JarvisEvent
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Phase 8 — bridges {@link AgentLiveFeed} entries into the Kafka audit
 * backbone via the api-gateway {@code /api/v1/audit/ingest} endpoint.
 *
 * <p>The desktop agent has no Kafka client (Pass 1 design). We translate
 * each {@link AgentEvent} into a {@link JarvisEvent} and POST it to the
 * gateway, which re-publishes to {@code jarvis.audit.events}. Failures
 * are silent at WARN — the audit path must never block agent operation.</p>
 */
class AuditForwarder(
    private val gatewayBaseUrl: String,
    private val agentId: String,
    private val feed: AgentLiveFeed,
    private val mapper: ObjectMapper = defaultMapper()
) : Closeable {

    private val log = LoggerFactory.getLogger(AuditForwarder::class.java)
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(2))
        .readTimeout(Duration.ofSeconds(5))
        .build()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jarvis-agent-audit-forwarder").also { it.isDaemon = true }
    }

    fun start() {
        feed.subscribe { event -> executor.submit { forward(event) } }
        log.info("AuditForwarder started → {}", urlFor("/api/v1/audit/ingest"))
    }

    private fun forward(event: AgentEvent) {
        val jarvisEvent = translate(event) ?: return
        val req = Request.Builder()
            .url(urlFor("/api/v1/audit/ingest"))
            .header("Content-Type", "application/json")
            .post(mapper.writeValueAsString(jarvisEvent)
                .toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log.warn("audit ingest HTTP {} for event {}", resp.code, event.eventId)
                }
            }
        }.onFailure { ex ->
            log.warn("audit ingest failed for event {}: {}", event.eventId, ex.message)
        }
    }

    private fun translate(event: AgentEvent): JarvisEvent? {
        val type = mapType(event.type) ?: return null
        return JarvisEvent.builder()
            .eventId(event.eventId)
            .eventType(type)
            .category(EventCategory.AUDIT)
            .severity(mapSeverity(event.severity))
            .source("desktop-agent")
            .agentId(agentId)
            .occurredAt(event.occurredAt)
            .payload(event.payload)
            .build()
    }

    private fun mapType(type: AgentEvent.Type?): AuditEventType? = when (type) {
        AgentEvent.Type.VOICE_SESSION_STARTED   -> AuditEventType.VOICE_SESSION_STARTED
        AgentEvent.Type.INTENT_CLASSIFIED       -> AuditEventType.VOICE_INTENT_CLASSIFIED
        AgentEvent.Type.COMMAND_QUEUED          -> AuditEventType.COMMAND_QUEUED
        AgentEvent.Type.CONFIRMATION_REQUESTED  -> AuditEventType.CONFIRMATION_REQUESTED
        AgentEvent.Type.COMMAND_EXECUTED        -> AuditEventType.COMMAND_EXECUTED
        AgentEvent.Type.MEMORY_WRITTEN          -> AuditEventType.MEMORY_WRITTEN
        AgentEvent.Type.CV_EVENT_RECEIVED       -> AuditEventType.CV_INCIDENT_RECORDED
        AgentEvent.Type.KILL_SWITCH_ENGAGED     -> AuditEventType.KILL_SWITCH_ENGAGED
        AgentEvent.Type.KILL_SWITCH_DISENGAGED  -> AuditEventType.KILL_SWITCH_DISENGAGED
        AgentEvent.Type.ERROR, AgentEvent.Type.DEGRADED_STATE -> null  // diagnostics, not audit
        null -> null
    }

    private fun mapSeverity(s: AgentEvent.Severity?): EventSeverity = when (s) {
        AgentEvent.Severity.WARN  -> EventSeverity.WARN
        AgentEvent.Severity.ERROR -> EventSeverity.ERROR
        else -> EventSeverity.INFO
    }

    private fun urlFor(path: String): String = gatewayBaseUrl.trimEnd('/') + path

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        fun defaultMapper(): ObjectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}
