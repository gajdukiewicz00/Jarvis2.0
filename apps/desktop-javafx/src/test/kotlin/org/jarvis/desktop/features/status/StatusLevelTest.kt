package org.jarvis.desktop.features.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins down the canonical vocabulary itself: exactly nine levels, each with a
 * label/colorToken/toneStyleClass, and the healthy/not-healthy split every
 * feature read-model relies on to decide whether a status counts as a failure.
 */
class StatusLevelTest {

    @Test
    fun `enum has exactly the canonical nine levels in the canonical order`() {
        assertEquals(
            listOf(
                StatusLevel.UP,
                StatusLevel.PROTECTED,
                StatusLevel.DEGRADED,
                StatusLevel.DOWN,
                StatusLevel.PARTIAL,
                StatusLevel.MOCK,
                StatusLevel.DISABLED,
                StatusLevel.UNAVAILABLE,
                StatusLevel.UNKNOWN
            ),
            StatusLevel.values().toList()
        )
    }

    @Test
    fun `every level has a non-blank label, colorToken, and toneStyleClass`() {
        StatusLevel.values().forEach { level ->
            assertTrue(level.label.isNotBlank(), "$level should have a human label")
            assertTrue(level.colorToken.startsWith("-jarvis-"), "$level colorToken should be a -jarvis-* token")
            assertTrue(
                level.toneStyleClass.startsWith("shell-status-tone-"),
                "$level toneStyleClass should be a shell-status-tone-* class"
            )
        }
    }

    @Test
    fun `UP, PROTECTED, MOCK, and DISABLED are the only healthy levels`() {
        val healthy = StatusLevel.values().filter { it.isHealthy }.toSet()
        assertEquals(setOf(StatusLevel.UP, StatusLevel.PROTECTED, StatusLevel.MOCK, StatusLevel.DISABLED), healthy)
    }

    @Test
    fun `DEGRADED, DOWN, PARTIAL, UNAVAILABLE, and UNKNOWN are never healthy`() {
        listOf(
            StatusLevel.DEGRADED,
            StatusLevel.DOWN,
            StatusLevel.PARTIAL,
            StatusLevel.UNAVAILABLE,
            StatusLevel.UNKNOWN
        ).forEach { level ->
            assertFalse(level.isHealthy, "$level must not be reported as healthy")
        }
    }

    @Test
    fun `PROTECTED reads as reachable-and-authenticated, not as a warning`() {
        // 401/403 responses prove the process is alive. PROTECTED must render
        // with the informational tone, never the warning/error tone used for
        // genuinely broken services.
        assertEquals("shell-status-tone-info", StatusLevel.PROTECTED.toneStyleClass)
        assertTrue(StatusLevel.PROTECTED.isHealthy)
    }

    @Test
    fun `UNAVAILABLE and UNKNOWN are distinct claims`() {
        // UNAVAILABLE = confirmed negative answer. UNKNOWN = no confirmed answer.
        // Collapsing them would let a screen claim "not present" when the truth
        // is merely "not yet known".
        assertTrue(StatusLevel.UNAVAILABLE != StatusLevel.UNKNOWN)
        assertFalse(StatusLevel.UNAVAILABLE.isHealthy)
        assertFalse(StatusLevel.UNKNOWN.isHealthy)
    }

    @Test
    fun `no two levels share the exact same label`() {
        val labels = StatusLevel.values().map { it.label }
        assertEquals(labels.size, labels.toSet().size, "Labels should be unique: $labels")
    }
}
