package org.jarvis.desktop.service.wake

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the new `paused` field on [WakeProviderDiagnostics]: it defaults to false
 * (so existing positional/named constructions keep working) and is emitted by
 * [WakeProviderDiagnostics.toJson].
 */
class WakeProviderDiagnosticsTest {

    private fun sample(paused: Boolean = false) = WakeProviderDiagnostics(
        providerId = "openwakeword",
        installed = true,
        reachable = true,
        models = listOf("hey_jarvis"),
        listening = true,
        lastWakeScore = 0.9,
        lastWakeDetectedAt = "2026-07-15T00:00:00Z",
        lastError = null,
        paused = paused
    )

    @Test
    fun `paused defaults to false`() {
        val diag = WakeProviderDiagnostics(
            providerId = "manual",
            installed = true,
            reachable = null,
            models = emptyList(),
            listening = false,
            lastWakeScore = null,
            lastWakeDetectedAt = null,
            lastError = null
        )
        assertFalse(diag.paused)
    }

    @Test
    fun `toJson includes the paused field`() {
        assertTrue(sample(paused = true).toJson().contains("\"paused\":true"))
        assertTrue(sample(paused = false).toJson().contains("\"paused\":false"))
    }
}
