package org.jarvis.agent.voice

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.commands.agent.AgentEvent
import org.jarvis.commands.voice.VoiceFeedback
import org.jarvis.commands.voice.VoiceSession
import org.jarvis.commands.voice.VoiceSessionStatus
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Phase 7 — desktop-side HTTP client to the voice-gateway voice-loop
 * endpoints. Used by the {@link WakeWordToVoiceBridge}.
 */
class VoiceLoopClient(
    private val voiceGatewayBaseUrl: String,
    private val agentId: String,
    private val userId: String,
    private val feed: AgentLiveFeed,
    private val mapper: ObjectMapper = defaultMapper()
) {
    private val log = LoggerFactory.getLogger(VoiceLoopClient::class.java)
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(35))
        .build()

    fun startSession(): VoiceSession? {
        val payload = mapOf("agentId" to agentId, "userId" to userId)
        val body = mapper.writeValueAsString(payload)
        val req = Request.Builder()
            .url(url("/api/v1/voice/sessions"))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log.warn("voice session start HTTP {}", resp.code)
                    return@use null
                }
                val text = resp.body?.string() ?: return@use null
                mapper.readValue(text, VoiceSession::class.java)
            }
        }.getOrElse {
            log.warn("voice session start failed: {}", it.message)
            null
        }?.also { session ->
            feed.emit(AgentEvent.info(
                agentId,
                AgentEvent.Type.VOICE_SESSION_STARTED,
                "voice session ${session.sessionId} started",
                mapOf("sessionId" to session.sessionId, "userId" to (session.userId ?: ""))
            ))
        }
    }

    fun submitUtterance(sessionId: String, transcript: String, locale: String? = "ru"): UtteranceReply? {
        val payload = mutableMapOf<String, Any>("transcript" to transcript)
        locale?.let { payload["locale"] = it }
        val body = mapper.writeValueAsString(payload)
        val req = Request.Builder()
            .url(url("/api/v1/voice/sessions/$sessionId/utterance"))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log.warn("voice utterance HTTP {} for session {}", resp.code, sessionId)
                    return@use null
                }
                val text = resp.body?.string() ?: return@use null
                mapper.readValue(text, UtteranceReply::class.java)
            }
        }.getOrElse {
            log.warn("voice utterance failed for session {}: {}", sessionId, it.message)
            null
        }?.also { reply ->
            feed.emit(AgentEvent.info(
                agentId,
                AgentEvent.Type.INTENT_CLASSIFIED,
                "intent=${reply.intent?.intent ?: "?"} source=${reply.intent?.source ?: "?"} status=${reply.sessionStatus}",
                mapOf(
                    "sessionId" to sessionId,
                    "intent" to (reply.intent?.intent ?: ""),
                    "source" to (reply.intent?.source ?: ""),
                    "status" to (reply.sessionStatus?.name ?: "")
                )
            ))
        }
    }

    fun endSession(sessionId: String): Boolean {
        val req = Request.Builder()
            .url(url("/api/v1/voice/sessions/$sessionId/end"))
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp -> resp.isSuccessful }
        }.getOrDefault(false)
    }

    private fun url(path: String): String = voiceGatewayBaseUrl.trimEnd('/') + path

    /** Mirror of voice-gateway's {@code UtteranceResponse}. */
    data class UtteranceReply(
        val sessionId: String? = null,
        val commandId: String? = null,
        val correlationId: String? = null,
        val sessionStatus: VoiceSessionStatus? = null,
        val feedback: VoiceFeedback? = null,
        val intent: IntentNode? = null
    ) {
        data class IntentNode(val intent: String? = null, val source: String? = null, val confidence: Double = 0.0)
    }

    companion object {
        fun defaultMapper(): ObjectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}
