package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Covers the "already running" fast-path guard at the very top of
 * [WakeWordDetector.start]. When isRunning is already set, start() must
 * short-circuit and return *before* touching the native Porcupine engine or
 * opening a microphone line — so seeding isRunning via reflection lets us
 * exercise the guard without any hardware or native library. The sibling
 * WakeWordDetector tests cover the not-running validation/teardown branches;
 * this fills the running-guard branch that they cannot reach safely.
 */
class WakeWordDetectorStartGuardTest {

    private fun detector() = WakeWordDetector(
        accessKey = "fake-access-key",
        keywordPaths = listOf("dummy.ppn"),
        builtInKeywords = null,
        onWakeWordDetected = {}
    )

    private fun WakeWordDetector.setRunning(running: Boolean) {
        WakeWordDetector::class.java.getDeclaredField("isRunning").apply {
            isAccessible = true
        }.setBoolean(this, running)
    }

    @Test
    fun `start is a no-op when the detector is already running`() {
        val d = detector()
        // Pretend a previous start() already booted the capture loop. The guard
        // must return immediately, never reaching Porcupine/microphone init.
        d.setRunning(true)

        assertDoesNotThrow { d.start() }

        // State is untouched by the guarded early return (still the initial STOPPED).
        assertEquals(WakeWordDetector.ListeningState.STOPPED, d.getState())
    }
}
