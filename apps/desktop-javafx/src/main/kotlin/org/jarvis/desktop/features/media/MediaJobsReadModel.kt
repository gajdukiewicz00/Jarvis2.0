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
 *  - list jobs  -> GET  /api/v1/media/jobs
 *  - cancel job -> POST /api/v1/media/jobs/{id}/cancel
 *
 * Artifact bytes are never fetched through this read model — [ApiClient] only
 * returns text responses, so [MediaJobsView] instead resolves an absolute
 * download URL from a job id + artifact index and hands it to the platform's
 * default handler (`GET /api/v1/media/jobs/{id}/artifacts/{index}`).
 */
class MediaJobsReadModel(
    private val apiClient: ApiClient
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
}
