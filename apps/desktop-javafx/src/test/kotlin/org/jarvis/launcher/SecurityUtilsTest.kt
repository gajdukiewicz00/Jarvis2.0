package org.jarvis.launcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecurityUtilsTest {

    @Test
    fun `maskJwt shortens a JWT-shaped token to its head and tail`() {
        val header = "a".repeat(20)
        val payload = "b".repeat(30)
        val signature = "c".repeat(25)
        val jwt = "$header.$payload.$signature"

        val masked = SecurityUtils.maskJwt("token=$jwt end")

        assertTrue(masked.contains("aaaa..."))
        assertTrue(masked.contains("cccc"))
        assertFalse(masked.contains(payload))
    }

    @Test
    fun `maskJwt leaves text without a JWT shape untouched`() {
        val text = "no tokens here, just plain text."
        assertEquals(text, SecurityUtils.maskJwt(text))
    }

    @Test
    fun `maskSecrets redacts key=value pairs for known sensitive keys`() {
        val masked = SecurityUtils.maskSecrets("password=hunter2 other=visible")
        assertTrue(masked.contains("password=***MASKED***"))
        assertTrue(masked.contains("other=visible"))
    }

    @Test
    fun `maskSecrets redacts JSON-shaped secret fields`() {
        val masked = SecurityUtils.maskSecrets("""{"apiKey": "sk-super-secret", "name": "ok"}""")
        assertTrue(masked.contains("\"apiKey\": \"***MASKED***\""))
        assertTrue(masked.contains("\"name\": \"ok\""))
    }

    @Test
    fun `maskEnvVarValue redacts only the configured secret keys`() {
        val masked = SecurityUtils.maskEnvVarValue(
            "JWT_SECRET=abc123 OTHER_VAR=visible",
            setOf("JWT_SECRET")
        )
        assertTrue(masked.contains("JWT_SECRET=***MASKED***"))
        assertTrue(masked.contains("OTHER_VAR=visible"))
    }

    @Test
    fun `maskSensitiveData applies JWT, secret, and env-var masking together`() {
        val secretKeys = setOf("POSTGRES_PASSWORD")
        val line = "POSTGRES_PASSWORD=s3cret token=abcdefghijklmnopqrst.abcdefghijklmnopqrst.abcdefghijklmnopqrst"

        val masked = SecurityUtils.maskSensitiveData(line, secretKeys)

        assertTrue(masked.contains("POSTGRES_PASSWORD=***MASKED***"))
        assertFalse(masked.contains("abcdefghijklmnopqrst.abcdefghijklmnopqrst.abcdefghijklmnopqrst"))
    }

    @Test
    fun `maskSensitiveData with no secret keys skips env-var masking but still masks JWT and key=value secrets`() {
        val masked = SecurityUtils.maskSensitiveData("token=hunter2 plain=ok")
        assertTrue(masked.contains("token=***MASKED***"))
        assertTrue(masked.contains("plain=ok"))
    }

    @Test
    fun `getCommonSecretKeys returns the expected fixed set`() {
        val keys = SecurityUtils.getCommonSecretKeys()
        assertTrue(keys.contains("JWT_SECRET"))
        assertTrue(keys.contains("POSTGRES_PASSWORD"))
        assertTrue(keys.contains("API_KEY"))
        assertEquals(12, keys.size)
    }
}
