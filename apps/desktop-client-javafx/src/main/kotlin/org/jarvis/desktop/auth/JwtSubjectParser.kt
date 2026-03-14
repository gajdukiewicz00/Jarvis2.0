package org.jarvis.desktop.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

/**
 * Reads the JWT subject from a token payload so the desktop client can keep a
 * stable user ID alongside the username returned by the auth API.
 */
object JwtSubjectParser {
    private val objectMapper = jacksonObjectMapper()

    fun extractSubject(token: String?): String? {
        if (token.isNullOrBlank()) {
            return null
        }

        val parts = token.split(".")
        if (parts.size < 2) {
            return null
        }

        return try {
            val payloadBytes = decodeBase64Url(parts[1])
            val payload = objectMapper.readTree(payloadBytes)
            payload.path("sub").asText(null)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase64Url(encoded: String): ByteArray {
        val padding = (4 - encoded.length % 4) % 4
        return Base64.getUrlDecoder().decode(encoded + "=".repeat(padding))
    }
}
