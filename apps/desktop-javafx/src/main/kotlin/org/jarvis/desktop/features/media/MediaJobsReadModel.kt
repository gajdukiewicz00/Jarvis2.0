package org.jarvis.desktop.features.media

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Read model for the Media Jobs panel.
 *
 * Wires the async media-processing job surface exposed by media-service
 * (roadmap wave-1):
 *  - list jobs        -> GET  /api/v1/media/jobs
 *  - cancel job       -> POST /api/v1/media/jobs/{id}/cancel
 *  - probe (sync)     -> POST /api/v1/media/probe
 *  - status/mode      -> GET  /api/v1/media/status
 *  - create sub job   -> POST /api/v1/media/jobs/russian-subtitles
 *  - create dub job   -> POST /api/v1/media/jobs/russian-dub-audio
 *  - create mux job   -> POST /api/v1/media/jobs/mux
 *
 * The three "create job" endpoints reply `202 Accepted`, which the shared
 * [ApiClient] does not treat as success — those calls go through [rawHttp]
 * instead (see its doc comment). Everything else here uses [apiClient] as
 * before.
 *
 * Artifact bytes are never fetched through [apiClient] — it only returns
 * text responses, so binary downloads also go through [rawHttp]
 * (`GET /api/v1/media/jobs/{id}/artifacts/{index}`), see [MediaJobsView].
 */
class MediaJobsReadModel(
    private val apiClient: ApiClient,
    private val rawHttp: MediaRawHttp = MediaRawHttp()
) {
    private val objectMapper = jacksonObjectMapper()

    data class Artifact(
        val kind: String,
        val contentType: String,
        val sizeBytes: Long,
        val note: String?
    )

    data class Job(
        val id: String,
        val type: String,
        val status: String,
        val inputFile: String,
        val artifacts: List<Artifact>,
        val createdAt: String?,
        val updatedAt: String?,
        val errorMessage: String?
    ) {
        val isTerminal: Boolean
            get() = status == "COMPLETED" || status == "FAILED" || status == "CANCELLED"
    }

    /** Reported operating mode of the media-service (see `MediaStatusController`). */
    data class MediaStatus(
        val enabled: Boolean,
        val jobStore: String,
        val providers: Map<String, String>
    ) {
        /** "MOCK" if every provider mode is `mock`, "REAL" if none are, "MIXED" otherwise. */
        val overallMode: String
            get() = when {
                providers.isEmpty() -> "UNKNOWN"
                providers.values.all { it.equals("mock", ignoreCase = true) } -> "MOCK"
                providers.values.none { it.equals("mock", ignoreCase = true) } -> "REAL"
                else -> "MIXED"
            }
    }

    /** Structured result of a synchronous `POST /media/probe` call (not a stored job). */
    data class ProbeSummary(
        val videoStreams: Int,
        val audioStreams: Int,
        val subtitleStreams: Int,
        val selectedAudioIndex: Int?,
        val durationSeconds: Double?
    )

    fun listJobs(): List<Job> {
        val root = objectMapper.readTree(apiClient.get("/media/jobs"))
        if (!root.isArray) return emptyList()
        return root.map(::parseJob)
    }

    /** Returns whether the backend reports the job as cancelled. */
    fun cancel(jobId: String): Boolean {
        val root = objectMapper.readTree(apiClient.post("/media/jobs/${encode(jobId)}/cancel", "{}"))
        return root.path("cancelled").let { it.isBoolean && it.asBoolean() }
    }

    /** Reports whether the service is enabled and which mode each provider runs in. */
    fun status(): MediaStatus {
        val root = objectMapper.readTree(apiClient.get("/media/status"))
        val providers = mutableMapOf<String, String>()
        root.path("providers").fields().forEach { (key, value) -> providers[key] = value.asText("") }
        return MediaStatus(
            enabled = root.path("enabled").asBoolean(false),
            jobStore = root.path("jobStore").asText("unknown"),
            providers = providers
        )
    }

    /** Synchronous stream probe — never creates a job (see `ProbeController`). */
    fun probe(inputFile: String, preferredLanguage: String?, overrideAudioIndex: Int?): ProbeSummary {
        val body = objectMapper.writeValueAsString(
            buildMap<String, Any> {
                put("inputFile", inputFile)
                preferredLanguage?.takeIf { it.isNotBlank() }?.let { put("preferredLanguage", it) }
                overrideAudioIndex?.let { put("overrideAudioIndex", it) }
            }
        )
        val root = objectMapper.readTree(apiClient.post("/media/probe", body))
        return ProbeSummary(
            videoStreams = root.path("video").size(),
            audioStreams = root.path("audio").size(),
            subtitleStreams = root.path("subtitle").size(),
            selectedAudioIndex = root.path("selectedAudioIndex").intOrNull(),
            durationSeconds = root.path("durationSeconds").doubleOrNull()
        )
    }

    /** Creates a Russian-subtitles job from a transcript artifact. */
    fun createSubtitlesJob(transcriptFile: String): Job {
        val body = objectMapper.writeValueAsString(mapOf("transcriptFile" to transcriptFile))
        return parseJob(objectMapper.readTree(rawHttp.postJson("/media/jobs/russian-subtitles", body)))
    }

    /** Creates a Russian dub-audio job from a (Russian) transcript artifact. */
    fun createDubJob(
        transcriptFile: String,
        voiceProfileMode: String?,
        voiceId: String?,
        consentConfirmed: Boolean
    ): Job {
        val body = objectMapper.writeValueAsString(
            buildMap<String, Any> {
                put("transcriptFile", transcriptFile)
                voiceProfileMode?.takeIf { it.isNotBlank() }?.let { put("voiceProfileMode", it) }
                voiceId?.takeIf { it.isNotBlank() }?.let { put("voiceId", it) }
                put("consentConfirmed", consentConfirmed)
            }
        )
        return parseJob(objectMapper.readTree(rawHttp.postJson("/media/jobs/russian-dub-audio", body)))
    }

    /** Creates a mux job that adds a Russian subtitle and/or dub track to a copy of the original. */
    fun createMuxJob(originalFile: String, subtitleFile: String?, dubAudioFile: String?, outputName: String?): Job {
        val body = objectMapper.writeValueAsString(
            buildMap<String, Any> {
                put("originalFile", originalFile)
                subtitleFile?.takeIf { it.isNotBlank() }?.let { put("subtitleFile", it) }
                dubAudioFile?.takeIf { it.isNotBlank() }?.let { put("dubAudioFile", it) }
                outputName?.takeIf { it.isNotBlank() }?.let { put("outputName", it) }
            }
        )
        return parseJob(objectMapper.readTree(rawHttp.postJson("/media/jobs/mux", body)))
    }

    /** Downloads one artifact's raw bytes (see [MediaRawHttp] for why this bypasses [ApiClient]). */
    fun downloadArtifact(jobId: String, index: Int): ByteArray =
        rawHttp.getBytes("/media/jobs/${encode(jobId)}/artifacts/$index")

    private fun parseJob(node: JsonNode): Job {
        return Job(
            id = node.path("id").asText(""),
            type = node.path("type").asText("UNKNOWN"),
            status = node.path("status").asText("UNKNOWN"),
            inputFile = node.path("inputFile").asText(""),
            artifacts = node.path("outputFiles").takeIf(JsonNode::isArray)?.map { a ->
                Artifact(
                    kind = a.path("kind").asText(""),
                    contentType = a.path("contentType").asText("application/octet-stream"),
                    sizeBytes = a.path("sizeBytes").asLong(0),
                    note = a.path("note").textOrNull()
                )
            } ?: emptyList(),
            createdAt = node.path("createdAt").textOrNull(),
            updatedAt = node.path("updatedAt").textOrNull(),
            errorMessage = node.path("errorMessage").textOrNull()
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }

    private fun JsonNode.intOrNull(): Int? = if (isMissingNode || isNull) null else asInt()

    private fun JsonNode.doubleOrNull(): Double? = if (isMissingNode || isNull) null else asDouble()
}
