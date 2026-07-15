package org.jarvis.desktop.service.wake

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the lifecycle-safety rules of [WakeEventGate] deterministically:
 * time is passed IN (no wall clock), so every accept/ignore decision is exact.
 */
class WakeEventGateTest {

    private fun wake(): WakeEvent =
        WakeEvent(provider = "openwakeword", model = "hey_jarvis", score = 0.9, device = "mic", timestampIso = "t")

    @Test
    fun `accepts the first wake when idle`() {
        val gate = WakeEventGate(isBusy = { false })
        assertTrue(gate.offer(wake(), nowMs = 0L))
    }

    @Test
    fun `ignores a duplicate wake within cooldown`() {
        val gate = WakeEventGate(isBusy = { false })
        assertTrue(gate.offer(wake(), nowMs = 0L))
        assertFalse(gate.offer(wake(), nowMs = 500L))   // 500ms < 2000ms cooldown
        assertFalse(gate.offer(wake(), nowMs = 1999L))  // still within cooldown
    }

    @Test
    fun `ignores wake events while a command is busy`() {
        var busy = true
        val gate = WakeEventGate(isBusy = { busy })
        assertFalse(gate.offer(wake(), nowMs = 0L))     // busy → ignore, does not even arm cooldown
        busy = false
        assertTrue(gate.offer(wake(), nowMs = 10L))     // now idle → accepted
    }

    @Test
    fun `accepts again after completion and cooldown elapse`() {
        val gate = WakeEventGate(isBusy = { false })
        assertTrue(gate.offer(wake(), nowMs = 0L))
        gate.markCompleted(nowMs = 100L)
        assertFalse(gate.offer(wake(), nowMs = 200L))       // within cooldown of completion
        assertTrue(gate.offer(wake(), nowMs = 100L + 2000L)) // cooldown past completion → accepted
    }

    @Test
    fun `cooldown is clamped into the 1500-3000ms window`() {
        val tooShort = WakeEventGate(isBusy = { false }, cooldownMs = 100L)
        assertTrue(tooShort.offer(wake(), nowMs = 0L))
        assertFalse(tooShort.offer(wake(), nowMs = 1000L)) // would pass at 100ms, but clamped up to 1500ms
        assertTrue(tooShort.offer(wake(), nowMs = 1500L))  // 1500ms is the floor

        val tooLong = WakeEventGate(isBusy = { false }, cooldownMs = 9000L)
        assertTrue(tooLong.offer(wake(), nowMs = 0L))
        assertTrue(tooLong.offer(wake(), nowMs = 3000L))   // clamped down to 3000ms ceiling
    }

    @Test
    fun `treats an isBusy failure conservatively as busy`() {
        val gate = WakeEventGate(isBusy = { throw IllegalStateException("boom") })
        assertFalse(gate.offer(wake(), nowMs = 0L))
    }
}
