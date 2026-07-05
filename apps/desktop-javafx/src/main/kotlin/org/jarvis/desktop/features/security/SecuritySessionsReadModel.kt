package org.jarvis.desktop.features.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Read model for the Security Sessions & Audit panel.
 *
 * security-service does not expose a live "list active sessions" endpoint —
 * session/token activity is instead surfaced through its OWNER-only audit
 * trail, which records every token issuance/rotation/revocation event. This
 * model wires that trail plus the two revoke actions it supports:
 *  - audit trail                    -> GET  /api/v1/security/auth/audit?limit=
 *  - revoke a single token          -> POST /api/v1/security/auth/revoke
 *  - revoke every session for a user -> POST /api/v1/security/auth/revoke-all/{userId}
 *
 * All three require the OWNER role; a non-owner caller gets a 403, surfaced
 * like any other gated call via [org.jarvis.desktop.api.AccessDeniedException].
 */
class SecuritySessionsReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class AuditEvent(
        val eventType: String,
        val userId: String?,
        val tokenReference: String,
        val occurredAt: String,
        val reason: String?
    )

    data class RevokeResult(val revoked: Boolean, val jti: String?, val tokenType: String?)

    data class RevokeAllResult(val userId: String, val revokedRefreshTokens: Int)

    fun listAudit(limit: Int = 50): List<AuditEvent> {
        val root = objectMapper.readTree(apiClient.get("/security/auth/audit?limit=$limit"))
        if (!root.isArray) return emptyList()
        return root.map { node ->
            AuditEvent(
                eventType = node.path("eventType").asText("UNKNOWN"),
                userId = node.path("userId").let { if (it.isMissingNode || it.isNull) null else it.asText() },
                tokenReference = node.path("tokenReference").asText(""),
                occurredAt = node.path("occurredAt").asText(""),
                reason = node.path("reason").textOrNull()
            )
        }
    }

    fun revokeToken(token: String, reason: String?): RevokeResult {
        val payload = objectMapper.createObjectNode().apply {
            put("token", token.trim())
            reason?.trim()?.takeIf { it.isNotEmpty() }?.let { put("reason", it) }
        }
        val root = objectMapper.readTree(apiClient.post("/security/auth/revoke", objectMapper.writeValueAsString(payload)))
        return RevokeResult(
            revoked = root.path("revoked").let { it.isBoolean && it.asBoolean() },
            jti = root.path("jti").textOrNull(),
            tokenType = root.path("tokenType").textOrNull()
        )
    }

    fun revokeAllForUser(userId: String, reason: String?): RevokeAllResult {
        val query = buildString {
            append("/security/auth/revoke-all/").append(encode(userId.trim()))
            reason?.trim()?.takeIf { it.isNotEmpty() }?.let { append("?reason=").append(encode(it)) }
        }
        val root = objectMapper.readTree(apiClient.post(query, "{}"))
        return RevokeAllResult(
            userId = root.path("userId").asText(userId),
            revokedRefreshTokens = root.path("revokedRefreshTokens").asInt(0)
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
