package org.jarvis.desktop.service.wake

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ManualOnlyProvider] does no detection, so pause/resume are pure no-ops and it
 * never reports itself paused — it must never throw or change state.
 */
class ManualOnlyProviderTest {

    private val config = WakeWordConfig()

    @Test
    fun `pause and resume are no-ops and never report paused`() {
        val provider = ManualOnlyProvider()
        provider.start(config) { }

        provider.pause()
        provider.resume()

        assertFalse(provider.diagnostics().paused)
        assertTrue(provider.status().state == WakeProviderState.READY)
    }
}
