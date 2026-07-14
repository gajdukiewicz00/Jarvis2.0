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

    @Test
    fun `classifyValidationFailure flags access-key and activation errors as INVALID`() {
        val invalidMessages = listOf(
            "AccessKey provided is invalid",
            "Activation error: your access key is not valid",
            "Unauthorized (401)",
            "credential rejected"
        )
        invalidMessages.forEach { msg ->
            val result = WakeWordDetector.classifyValidationFailure(
                WakeWordDetector.FailureDetails(message = msg, messageStack = emptyList(), nativeCode = null)
            )
            assertEquals(AccessKeyValidation.INVALID, result.status, "expected INVALID for '$msg'")
            assertEquals(msg, result.reason)
        }
    }

    @Test
    fun `classifyValidationFailure treats non-key errors as UNKNOWN`() {
        val result = WakeWordDetector.classifyValidationFailure(
            WakeWordDetector.FailureDetails(
                message = "Keyword file (.ppn) belongs to a different version",
                messageStack = listOf("[0] d3ff828 00000136: init failed"),
                nativeCode = "00000136"
            )
        )

        assertEquals(AccessKeyValidation.UNKNOWN, result.status)
        assertEquals("Keyword file (.ppn) belongs to a different version", result.reason)
    }

    @Test
    fun `classifyValidationFailure inspects the message stack too`() {
        val result = WakeWordDetector.classifyValidationFailure(
            WakeWordDetector.FailureDetails(
                message = null,
                messageStack = listOf("[0] activation limit reached for this AccessKey"),
                nativeCode = null
            )
        )

        assertEquals(AccessKeyValidation.INVALID, result.status)
    }

    @Test
    fun `activation-refused exception CLASS is INVALID even with an opaque message`() {
        // The real-world failure: PorcupineActivationRefusedException whose human message
        // is just "Initialization failed:" with an opaque native stack. The exception
        // CLASS is the reliable signal, so this must resolve to INVALID with a readable reason.
        val result = WakeWordDetector.classifyValidationFailure(
            WakeWordDetector.FailureDetails(
                message = "Initialization failed:",
                messageStack = listOf("d3ff828 00000136: e390eff"),
                nativeCode = "00000136",
                exceptionClass = "ai.picovoice.porcupine.PorcupineActivationRefusedException"
            )
        )

        assertEquals(AccessKeyValidation.INVALID, result.status)
        assertTrue(
            result.reason?.contains("activation refused", ignoreCase = true) == true,
            "reason should name the activation refusal, was: ${result.reason}"
        )
    }

    @Test
    fun `describeFailure captures the exception class name`() {
        val details = WakeWordDetector.describeFailure(IllegalStateException("no mic"))
        assertEquals("java.lang.IllegalStateException", details.exceptionClass)
    }

    @Test
    fun `activation-limit exception CLASS is INVALID`() {
        val result = WakeWordDetector.classifyValidationFailure(
            WakeWordDetector.FailureDetails(
                message = "Initialization failed:",
                messageStack = emptyList(),
                nativeCode = null,
                exceptionClass = "ai.picovoice.porcupine.PorcupineActivationLimitException"
            )
        )
        assertEquals(AccessKeyValidation.INVALID, result.status)
        assertTrue(result.reason?.contains("limit", ignoreCase = true) == true)
    }
}
