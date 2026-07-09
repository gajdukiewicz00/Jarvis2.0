package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Exercises the *active* state-machine transitions of [WakeWordDetector] that
 * the plain no-op guards can't reach without booting the native Porcupine
 * engine or a live microphone. We seed the internal state via reflection (the
 * only branch that normally sets LISTENING is [WakeWordDetector.start], which
 * requires native/audio resources) and then drive pause -> resume and the
 * full stop() teardown path. No native library is loaded and no audio line is
 * opened: the seeded resources (porcupine/targetDataLine/audioThread) stay
 * null, so stop() only walks its null-safe cleanup.
 */
class WakeWordDetectorStateTransitionTest {

    private fun detector() = WakeWordDetector(
        accessKey = "fake-access-key",
        keywordPaths = listOf("dummy.ppn"),
        builtInKeywords = null,
        onWakeWordDetected = {}
    )

    private fun WakeWordDetector.setState(state: WakeWordDetector.ListeningState) {
        WakeWordDetector::class.java.getDeclaredField("currentState").apply {
            isAccessible = true
        }.set(this, state)
    }

    private fun WakeWordDetector.setRunning(running: Boolean) {
        WakeWordDetector::class.java.getDeclaredField("isRunning").apply {
            isAccessible = true
        }.setBoolean(this, running)
    }

    @Test
    fun `pause transitions from LISTENING to PAUSED`() {
        val d = detector()
        d.setState(WakeWordDetector.ListeningState.LISTENING)

        d.pause()

        assertEquals(WakeWordDetector.ListeningState.PAUSED, d.getState())
    }

    @Test
    fun `resume transitions from PAUSED back to LISTENING`() {
        val d = detector()
        d.setState(WakeWordDetector.ListeningState.PAUSED)

        d.resume()

        assertEquals(WakeWordDetector.ListeningState.LISTENING, d.getState())
    }

    @Test
    fun `pause then resume round-trips the listening state`() {
        val d = detector()
        d.setState(WakeWordDetector.ListeningState.LISTENING)

        d.pause()
        assertEquals(WakeWordDetector.ListeningState.PAUSED, d.getState())

        d.resume()
        assertEquals(WakeWordDetector.ListeningState.LISTENING, d.getState())
    }

    @Test
    fun `stop while running tears down and returns to STOPPED`() {
        val d = detector()
        // Simulate a started detector without any native/audio resources attached.
        d.setRunning(true)
        d.setState(WakeWordDetector.ListeningState.LISTENING)

        assertDoesNotThrow { d.stop() }

        assertEquals(WakeWordDetector.ListeningState.STOPPED, d.getState())
        val runningField = WakeWordDetector::class.java.getDeclaredField("isRunning").apply {
            isAccessible = true
        }
        assertFalse(runningField.getBoolean(d), "stop() must clear isRunning")
    }
}
