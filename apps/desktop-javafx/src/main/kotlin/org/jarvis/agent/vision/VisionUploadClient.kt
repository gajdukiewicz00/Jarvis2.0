package org.jarvis.agent.vision

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.commands.agent.AgentEvent
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Phase 10 — desktop-agent client for the
 * {@code POST /api/v1/vision/frames} ingest endpoint.
 *
 * <p>Pass 1 sends only metadata — capture type, the active window title,
 * an optional local URI to the frame on disk, and a timestamp. Pass 2
 * will add an upload variant for cases where the operator wants
 * off-host re-analysis.</p>
 *
 * <p>Each successful upload emits a {@link AgentEvent.Type#CV_EVENT_RECEIVED}
 * entry on the {@link AgentLiveFeed} so the desktop panel and the
 * Phase 8 audit forwarder can surface the activity.</p>
 */
class VisionUploadClient(
    private val visionBaseUrl: String,
    private val agentId: String,
    private val userId: String,
    private val feed: AgentLiveFeed,
    private val mapper: ObjectMapper = defaultMapper()
) {
    private val log = LoggerFactory.getLogger(VisionUploadClient::class.java)
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(2))
        .readTimeout(Duration.ofSeconds(5))
        .build()

    /** @return frameId on success, null on refusal / failure. */
    fun publishFrame(captureType: String,
                     contextWindow: String? = null,
                     imageUri: String? = null,
                     timestamp: Instant = Instant.now()): String? {
        val payload = mutableMapOf<String, Any?>(
            "agentId" to agentId,
            "userId" to userId,
            "captureType" to captureType,
            "timestamp" to timestamp.toString()
        )
        contextWindow?.let { payload["contextWindow"] = it }
        imageUri?.let { payload["imageUri"] = it }

        val body = mapper.writeValueAsString(payload)
        val req = Request.Builder()
            .url(urlFor("/api/v1/vision/frames"))
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string()
                if (!resp.isSuccessful) {
                    log.warn("vision frame upload HTTP {} — body={}", resp.code, text)
                    return@use null
                }
                val json = if (text == null || text.isBlank()) emptyMap<String, Any>()
                           else mapper.readValue(text, Map::class.java)
                val frameId = json["frameId"] as? String
                if (frameId != null) {
                    feed.emit(AgentEvent.info(
                        agentId,
                        AgentEvent.Type.CV_EVENT_RECEIVED,
                        "frame ingested: type=$captureType id=$frameId",
                        mapOf("frameId" to frameId,
                              "captureType" to captureType,
                              "context" to (contextWindow ?: ""))
                    ))
                }
                frameId
            }
        }.getOrElse {
            log.warn("vision frame upload failed: {}", it.message)
            null
        }
    }

    private fun urlFor(path: String): String = visionBaseUrl.trimEnd('/') + path

    companion object {
        fun defaultMapper(): ObjectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}
