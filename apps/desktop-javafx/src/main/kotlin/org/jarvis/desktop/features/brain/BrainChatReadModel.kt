package org.jarvis.desktop.features.brain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient

/**
 * Read model for the Brain/AI chat panel.
 *
 * Talks to the 14B brain through the gateway-proxied `POST /api/v1/llm/chat`
 * endpoint (relative to the desktop client's `/api/v1` base URL). Parsing is
 * defensive: the gateway response shape varies between deployments, so we probe
 * several common reply fields before falling back to the raw body.
 */
class BrainChatReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class ChatReply(
        val message: String,
        val model: String?
    )

    fun chat(prompt: String): ChatReply {
        val payload = objectMapper.createObjectNode().apply {
            putArray("messages").apply {
                addObject().apply {
                    put("role", "user")
                    put("content", prompt.trim())
                }
            }
            put("stream", false)
        }
        val response = apiClient.post(
            "/llm/chat",
            objectMapper.writeValueAsString(payload),
            CHAT_READ_TIMEOUT_MS
        )
        return parseReply(response)
    }

    private fun parseReply(body: String): ChatReply {
        val root = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return ChatReply(body.trim().ifBlank { "(empty reply)" }, null)

        val message = firstNonBlank(
            root.at("/choices/0/message/content").textOrNull(),
            root.path("message").path("content").textOrNull(),
            root.path("content").textOrNull(),
            root.path("response").textOrNull(),
            root.path("reply").textOrNull(),
            root.path("text").textOrNull(),
            root.path("answer").textOrNull()
        ) ?: body.trim().ifBlank { "(empty reply)" }

        val model = firstNonBlank(
            root.path("model").textOrNull(),
            root.path("modelId").textOrNull(),
            root.at("/usage/model").textOrNull()
        )
        return ChatReply(message, model)
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }

    private companion object {
        const val CHAT_READ_TIMEOUT_MS = 120_000
    }
}
