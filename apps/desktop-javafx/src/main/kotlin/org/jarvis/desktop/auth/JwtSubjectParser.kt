package org.jarvis.desktop.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import java.util.Base64

/**
 * Reads the JWT subject from a token payload so the desktop client can keep a
 * stable user ID alongside the username returned by the auth API.
 */
object JwtSubjectParser {
    private val objectMapper = jacksonObjectMapper()

    fun extractSubject(token: String?): String? {
        val payload = parsePayload(token) ?: return null
        return payload.path("sub").asText(null)?.takeIf { it.isNotBlank() }
    }

    fun extractExpirationEpochSeconds(token: String?): Long? {
        val payload = parsePayload(token) ?: return null
        val expirationNode = payload.path("exp")
        if (expirationNode.isMissingNode || expirationNode.isNull) {
            return null
        }

        return when {
            expirationNode.canConvertToLong() -> expirationNode.longValue()
            expirationNode.isTextual -> expirationNode.asText().toLongOrNull()
            else -> null
        }
    }

    private fun parsePayload(token: String?): JsonNode? {
        if (token.isNullOrBlank()) {
            return null
        }

        val parts = token.split(".")
        if (parts.size < 2) {
            return null
        }

        return try {
            val payloadBytes = decodeBase64Url(parts[1])
            objectMapper.readTree(payloadBytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase64Url(encoded: String): ByteArray {
        val padding = (4 - encoded.length % 4) % 4
        return Base64.getUrlDecoder().decode(encoded + "=".repeat(padding))
    }
}
