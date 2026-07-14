package org.jarvis.desktop.service

import ai.picovoice.porcupine.PorcupineException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hardware-free coverage for [WakeWordDetector.describeFailure] — the helper the
 * production `attempt` seam uses to turn a caught Throwable into a captured
 * failure (message + Porcupine 4.x message stack + parsed native code).
 */
class WakeWordDetectorFailureDetailsTest {

    @Test
    fun `captures message stack and parses native code from a PorcupineException`() {
        val stack = arrayOf("[0] d3ff828 00000136: init failed", "[1] more detail")
        val ex = PorcupineException("boom", stack)

        val details = WakeWordDetector.describeFailure(ex)

        assertEquals(stack.toList(), details.messageStack)
        assertEquals("00000136", details.nativeCode)
        assertTrue(details.message?.contains("boom") == true)
    }

    @Test
    fun `non-porcupine throwable has empty stack and null native code`() {
        val details = WakeWordDetector.describeFailure(IllegalStateException("no mic"))

        assertTrue(details.messageStack.isEmpty())
        assertNull(details.nativeCode)
        assertEquals("no mic", details.message)
    }

    @Test
    fun `extractNativeCode finds the eight-hex token and ignores shorter tokens`() {
        assertEquals(
            "00000136",
            WakeWordDetector.extractNativeCode(listOf("[0] d3ff828 00000136: bad"))
        )
        assertNull(WakeWordDetector.extractNativeCode(listOf("no code here", "1234")))
    }
}
