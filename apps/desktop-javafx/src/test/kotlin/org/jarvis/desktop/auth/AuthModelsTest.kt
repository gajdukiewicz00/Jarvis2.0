package org.jarvis.desktop.auth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuthModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `AuthResponse round trips through JSON`() {
        val original = AuthResponse(
            accessToken = "access-1",
            refreshToken = "refresh-1",
            expiresIn = 3600,
            username = "alice",
            role = "USER"
        )

        val decoded = json.decodeFromString(AuthResponse.serializer(), json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `LoginRequest serializes username and password`() {
        val encoded = json.encodeToString(LoginRequest("alice", "s3cret"))
        assertEquals("""{"username":"alice","password":"s3cret"}""", encoded)
    }

    @Test
    fun `RegisterRequest defaults role to USER when not specified`() {
        val request = RegisterRequest(username = "bob", password = "hunter2")
        assertEquals("USER", request.role)

        // The default Json instance has encodeDefaults=false, so a role left at its
        // default value ("USER") is omitted entirely rather than written out.
        val encoded = json.encodeToString(request)
        assertEquals("""{"username":"bob","password":"hunter2"}""", encoded)

        val explicit = json.encodeToString(request.copy(role = "ADMIN"))
        assertEquals("""{"username":"bob","password":"hunter2","role":"ADMIN"}""", explicit)
    }

    @Test
    fun `RefreshRequest serializes the refresh token field`() {
        val encoded = json.encodeToString(RefreshRequest("refresh-xyz"))
        assertEquals("""{"refreshToken":"refresh-xyz"}""", encoded)
    }
}
