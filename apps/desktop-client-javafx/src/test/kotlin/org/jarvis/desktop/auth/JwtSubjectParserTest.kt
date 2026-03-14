package org.jarvis.desktop.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64

class JwtSubjectParserTest {

    @Test
    @DisplayName("extractSubject returns JWT subject when token payload is valid")
    fun extractSubjectReturnsJwtSubject() {
        val token = jwt("""{"sub":"42","username":"alice"}""")

        assertEquals("42", JwtSubjectParser.extractSubject(token))
    }

    @Test
    @DisplayName("extractSubject returns null for malformed tokens")
    fun extractSubjectReturnsNullForMalformedTokens() {
        assertNull(JwtSubjectParser.extractSubject("not-a-jwt"))
        assertNull(JwtSubjectParser.extractSubject("a.b"))
    }

    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val body = encoder.encodeToString(payload.toByteArray())
        return "$header.$body.signature"
    }
}
