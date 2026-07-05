package org.jarvis.desktop.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `TokenResponse round trips through JSON`() {
        val original = TokenResponse(token = "jwt-abc", expiresIn = 3600)
        val decoded = json.decodeFromString(TokenResponse.serializer(), json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `TokenRequest defaults claims to an empty map`() {
        val request = TokenRequest(username = "alice")
        assertTrue(request.claims.isEmpty())

        // The default Json instance has encodeDefaults=false, so a claims map left at
        // its default (empty) value is omitted entirely rather than written out as {}.
        val encoded = json.encodeToString(request)
        assertEquals("""{"username":"alice"}""", encoded)
    }

    @Test
    fun `TokenRequest serializes custom claims`() {
        val request = TokenRequest(username = "bob", claims = mapOf("role" to "ADMIN"))
        val decoded = json.decodeFromString(TokenRequest.serializer(), json.encodeToString(request))
        assertEquals("ADMIN", decoded.claims["role"])
    }
}
