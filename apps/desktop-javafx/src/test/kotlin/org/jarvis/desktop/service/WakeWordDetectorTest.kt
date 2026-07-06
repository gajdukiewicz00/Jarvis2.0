package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * [WakeWordDetector.start] normally boots the native Porcupine engine and a
 * live microphone capture thread — neither of which this test may safely
 * exercise (no wake-word model/access key here, and opening a real
 * microphone line would be both environment-dependent and a privacy
 * concern). This covers only the deterministic, hardware-free branches:
 * the initial state, the no-op guards, and the "no keyword source"
 * validation error that fires before any native/audio resource is touched.
 */
class WakeWordDetectorTest {

    private fun detector(
        keywordPaths: List<String>? = null,
        builtInKeywords: List<ai.picovoice.porcupine.Porcupine.BuiltInKeyword>? = null
    ) = WakeWordDetector(
        accessKey = "fake-access-key",
        keywordPaths = keywordPaths,
        builtInKeywords = builtInKeywords,
        onWakeWordDetected = {}
    )

    @Test
    fun `initial state is STOPPED`() {
        assertEquals(WakeWordDetector.ListeningState.STOPPED, detector().getState())
    }

    @Test
    fun `start throws when neither keywordPaths nor builtInKeywords are supplied`() {
        val ex = assertThrows(IllegalStateException::class.java) { detector().start() }
        assertEquals("Either keywordPaths or builtInKeywords must be provided", ex.message)
        // Must fail before ever reaching native Porcupine/audio initialization.
        assertEquals(WakeWordDetector.ListeningState.STOPPED, detector().getState())
    }

    @Test
    fun `stop is a safe no-op when never started`() {
        assertDoesNotThrow { detector().stop() }
    }

    @Test
    fun `pause is a no-op when not currently listening`() {
        val d = detector()
        d.pause()
        assertEquals(WakeWordDetector.ListeningState.STOPPED, d.getState())
    }

    @Test
    fun `resume is a no-op when not currently paused`() {
        val d = detector()
        d.resume()
        assertEquals(WakeWordDetector.ListeningState.STOPPED, d.getState())
    }

    @Test
    fun `createWithBuiltInKeywords wires builtInKeywords through the factory`() {
        val d = WakeWordDetector.createWithBuiltInKeywords(
            accessKey = "fake-access-key",
            keywords = listOf(ai.picovoice.porcupine.Porcupine.BuiltInKeyword.JARVIS)
        ) {}
        assertEquals(WakeWordDetector.ListeningState.STOPPED, d.getState())
    }

    /**
     * Regression test for finding #52: `isRunning` is written by start()/stop() on the
     * caller thread and read in the tight polling loop of processMicrophoneAudio() on the
     * dedicated WakeWordDetectorThread, with no synchronized/Lock/AtomicBoolean guarding it.
     * Without @Volatile (or an equivalent JMM happens-before mechanism), there is no
     * guarantee the audio thread ever observes stop() setting isRunning = false, which can
     * pin it in a tight retry loop against a closed TargetDataLine past the 1s join() window.
     * `currentState` in the same class is correctly annotated @Volatile for the same reason.
     */
    @Test
    fun `isRunning must be volatile so stop is visible to the audio capture thread`() {
        val field = WakeWordDetector::class.java.getDeclaredField("isRunning")

        assertTrue(
            Modifier.isVolatile(field.modifiers),
            "isRunning is written by start()/stop() on the caller thread and read in the " +
                "polling loop on WakeWordDetectorThread; it must be @Volatile (like " +
                "currentState) to guarantee the audio thread observes stop() promptly."
        )
    }
}
