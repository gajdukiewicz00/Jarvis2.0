package org.jarvis.desktop.features.panic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient

/**
 * Thin client for the global panic / kill-switch surface (EPIC 3):
 *  - status -> GET  /api/v1/agent/panic
 *  - engage -> POST /api/v1/agent/panic
 *  - clear  -> POST /api/v1/agent/panic/clear
 *
 * Mirrors [org.jarvis.desktop.features.security.SecurityReadModel]'s
 * tolerant-JSON-parsing style so a non-JSON or unexpected body degrades to a
 * readable message instead of throwing.
 */
class PanicControlService(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class PanicSnapshot(
        val engaged: Boolean,
        val actor: String?,
        val reason: String?,
        val detail: String
    )

    fun status(): PanicSnapshot = parse(apiClient.get("/agent/panic"))

    fun engage(reason: String = "Panic engaged from desktop shell"): PanicSnapshot {
        val body = objectMapper.writeValueAsString(mapOf("actor" to ACTOR, "reason" to reason))
        return parse(apiClient.post("/agent/panic", body))
    }

    fun clear(): PanicSnapshot {
        val body = objectMapper.writeValueAsString(mapOf("actor" to ACTOR))
        return parse(apiClient.post("/agent/panic/clear", body))
    }

    private fun parse(body: String): PanicSnapshot {
        val root = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return PanicSnapshot(engaged = false, actor = null, reason = null, detail = body.trim())

        val engaged = root.path("engaged").let { if (it.isBoolean) it.asBoolean() else false }
        val actor = root.path("actor").textOrNull()
        val reason = root.path("reason").textOrNull()
        val detail = buildString {
            if (engaged) {
                append("Panic engaged")
                actor?.let { append(" by $it") }
                reason?.let { append(" — $it") }
            } else {
                append("Panic is clear")
                actor?.let { append(" (cleared by $it)") }
            }
        }
        return PanicSnapshot(engaged, actor, reason, detail)
    }

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }

    private companion object {
        const val ACTOR = "desktop-shell"
    }
}
